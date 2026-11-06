package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NeuralRankingTest {
    @Test
    fun `an explicitly added matching spelling reserves a slot outside the neural vocabulary`() {
        assertEquals(listOf("zone", "zodiac", "zorpquill"), NeuralRanking.rank(
            listOf("zone", "zodiac", "zoo"), emptyList(), emptyList(), "zo", emptySet(), added = listOf("zorpquill")))
        assertEquals(listOf("zone", "zodiac", "zoo"), NeuralRanking.rank(
            listOf("zone", "zodiac", "zoo"), emptyList(), emptyList(), "", emptySet(), added = listOf("zorpquill")))
        assertEquals(listOf("zone", "zodiac", "zoo"), NeuralRanking.rank(
            listOf("zone", "zodiac", "zoo"), emptyList(), emptyList(), "zo", setOf("zorpquill"), added = listOf("zorpquill")))
        assertEquals(listOf(NeuralRanking.Choice("zorpquill", false)), NeuralRanking.rankWithSources(
            listOf("zone", "zodiac"), emptyList(), emptyList(), "zo", emptySet(), 1, added = listOf("zorpquill")))
    }

    @Test
    fun `neural words lead and fallback fills only empty slots`() {
        assertEquals(listOf("later", "soon", "today"), rank(listOf("later", "soon"), listOf("today", "tomorrow", "later")))
        assertEquals(listOf("later", "soon", "today"), rank(listOf("later", "soon", "today", "tomorrow"), listOf("fallback")))
    }

    @Test
    fun `two personal observations reserve a slot ahead of the third neural word`() {
        assertEquals(listOf("later", "soon", "zorp"), rank(listOf("later", "soon", "today"), learned = listOf("zorp" to 2)))
        assertEquals(listOf("later", "soon", "today"), rank(listOf("later", "soon", "today"), learned = listOf("zorp" to 1)))
    }

    @Test
    fun `the personal slot survives an emoji or correction taking a word slot`() {
        assertEquals(listOf("later", "zorp"), rank(listOf("later", "soon", "today"), learned = listOf("zorp" to 2), limit = 2))
        assertEquals(listOf("zorp"), rank(listOf("later", "soon", "today"), learned = listOf("zorp" to 2), limit = 1))
        assertTrue(rank(listOf("later"), learned = listOf("zorp" to 2), limit = 0).isEmpty())
    }

    @Test
    fun `a personal word already in neural top three does not displace another word`() {
        assertEquals(listOf("later", "soon", "today"), rank(listOf("later", "soon", "today"), learned = listOf("today" to 4)))
    }

    @Test
    fun `the strongest eligible personal follower wins with lexical ties`() {
        assertEquals(listOf("later", "soon", "alpha"), rank(listOf("later", "soon", "today"), learned = listOf("zorp" to 2, "beta" to 5, "alpha" to 5)))
        assertEquals(listOf("later", "soon", "beta"), rank(listOf("later", "soon", "today"), learned = listOf("alpha" to 5, "beta" to 3), blocked = setOf("alpha")))
    }

    @Test
    fun `every source uses whole word prefix profanity and one letter filters`() {
        assertEquals(listOf("a", "i", "can't"), rank(listOf("x", "A", "I", "Can’t", "two words", "42", "wow!", "🤔", "CAN'T")))
        assertEquals(listOf("there", "then", "them"), rank(listOf("These", "THERE", "other", "then", "them"), learned = listOf("other" to 10, "these" to 5), prefix = "TH", blocked = setOf("THESE")))
        assertEquals(listOf("allowed", "fallback", "personal"), rank(listOf("blocked", "allowed"), listOf("blocked", "fallback"), listOf("blocked" to 8, "personal" to 2), blocked = setOf("blocked")))
    }

    @Test
    fun `source labels can use the same eligible neural order including filtered backfill`() {
        val neural = listOf("blocked", "A", "x", "I", "later")
        val eligible = NeuralRanking.eligibleWords(neural, "", setOf("blocked"))
        assertEquals(listOf("a", "i", "later"), eligible)
        assertEquals(eligible, rank(neural, blocked = setOf("blocked")))
    }

    @Test
    fun `source labels distinguish neural backfill personal reservation and ordinary fallback`() {
        assertEquals(
            listOf(NeuralRanking.Choice("later", true), NeuralRanking.Choice("soon", true), NeuralRanking.Choice("zorp", false)),
            NeuralRanking.rankWithSources(listOf("blocked", "later", "soon", "today", "zorp"), emptyList(), listOf("zorp" to 2), "", setOf("blocked")),
        )
        assertEquals(
            listOf(NeuralRanking.Choice("later", true), NeuralRanking.Choice("today", false)),
            NeuralRanking.rankWithSources(listOf("later"), listOf("later", "today"), emptyList(), "", emptySet()),
        )
    }

    @Test
    fun `the pinned models excluded tokens never become neural or personal suggestions`() {
        val model = File("src/main/assets/${NeuralAsset.FILE_NAME}").inputStream().use(NeuralAsset::load)
        val excluded = (0 until model.vocabSize).map(model::token).filter(SuggestionWordRules::isExcluded)
        assertEquals("pinned vocabulary exclusions", 2, excluded.size)
        assertTrue(excluded.all { CifgModel.isOfferable(it) })
        for (word in excluded) {
            assertEquals(listOf("later", "soon", "today"), rank(listOf(word, "later", "soon", "today")))
            assertEquals(listOf("later", "soon", "today"), rank(listOf("later", "soon", "today"), learned = listOf(word to 10)))
            assertEquals(listOf("later"), rank(emptyList(), listOf(word, "later")))
            assertTrue(NeuralRanking.eligibleWords(listOf(word.uppercase(), "fine-$word"), "", emptySet()).isEmpty())
        }
    }

    @Test
    fun `one verified explicit observation reserves a filtered personal slot with Chime on or off`() {
        val followers = listOf(PersonalFollower("zorp", 1, 1, 1, 1))
        for (neural in listOf(emptyList(), listOf("later", "soon", "today"))) {
            val choices = NeuralRanking.rankWithSources(neural, listOf("later", "soon", "today"),
                emptyList(), "", emptySet(), followers = followers)
            assertEquals(listOf("later", "soon", "zorp"), choices.map { it.word })
            assertEquals(NeuralRanking.Choice("zorp", false, PersonalRanking.Source.PERSONAL), choices.last())
        }
        assertEquals(listOf("later", "soon", "today"), NeuralRanking.rankWithSources(
            listOf("later", "soon", "today"), emptyList(), emptyList(), "", setOf("zorp"), followers = followers).map { it.word })
        assertEquals(emptyList<NeuralRanking.Choice>(), NeuralRanking.rankWithSources(
            emptyList(), emptyList(), emptyList(), "th", emptySet(), followers = followers))
    }

    @Test
    fun `two personal slots retain a neural word and smaller strips reserve only one`() {
        val followers = listOf(PersonalFollower("alpha", 2, 0, 2, 1), PersonalFollower("beta", 1, 1, 1, 1))
        val policy = PersonalRanking.Policy(1, 2)
        for (limit in 1..3) {
            val result = NeuralRanking.rankWithSources(listOf("later", "soon", "today"), emptyList(),
                emptyList(), "", emptySet(), limit, followers = followers, personalPolicy = policy)
            assertEquals(limit, result.size)
            assertEquals(if (limit == 3) 2 else 1, result.count { !it.neural })
            if (limit > 1) assertTrue(result.first().neural)
        }
    }

    @Test
    fun `personal evidence does not sum legacy totals or bypass added prefix reservation`() {
        val followers = listOf(PersonalFollower("zone", 1, 1, 1, 1))
        assertEquals(listOf("zoo", "zodiac", "zorpquill"), NeuralRanking.rankWithSources(
            listOf("zoo", "zodiac", "zoom"), emptyList(), listOf("zone" to 4), "zo", emptySet(),
            added = listOf("zorpquill"), followers = followers).map { it.word })
        assertEquals(listOf("later", "soon", "legacy"), NeuralRanking.rankWithSources(
            listOf("later", "soon", "today"), emptyList(), listOf("legacy" to 3, "personal" to 2), "", emptySet(),
            followers = listOf(PersonalFollower("personal", 2, 0, 2, 1))).map { it.word })
    }

    @Test
    fun `stronger new evidence is ranked before making any legacy reservation`() {
        assertEquals(listOf("later", "soon", "personal"), NeuralRanking.rank(
            listOf("later", "soon", "today"), emptyList(), listOf("legacy" to 2), "", emptySet(),
            followers = listOf(PersonalFollower("personal", 3, 1, 3, 1))))
    }

    @Test
    fun `new followers cannot bypass exclusion offerability eligibility or context policy`() {
        val model = File("src/main/assets/${NeuralAsset.FILE_NAME}").inputStream().use(NeuralAsset::load)
        val excluded = (0 until model.vocabSize).map(model::token).first(SuggestionWordRules::isExcluded)
        val invalid = listOf(excluded, "two words", "42", "x", "wow!")
            .map { PersonalFollower(it, 9, 1, 1, 1) } +
            listOf(PersonalFollower("premature", 1, 0, 1, 1), PersonalFollower("longcontext", 2, 1, 1, 3),
                PersonalFollower("oncebehindtwo", 1, 0, 1, 2))
        assertEquals(listOf("later", "soon", "today"), NeuralRanking.rank(
            listOf("later", "soon", "today"), emptyList(), emptyList(), "", emptySet(), followers = invalid,
            personalPolicy = PersonalRanking.Policy(1, 1)))
    }

    @Test
    fun `confirmed policy uses three own context words and reserves one slot`() {
        assertEquals("personal-learning-v2-context3-slots1-once2", NeuralRanking.PERSONAL_POLICY_ID)
        assertEquals(PersonalRanking.Policy(3, 1), NeuralRanking.PERSONAL_POLICY)
        assertEquals(listOf("later", "soon", "specific"), NeuralRanking.rank(
            listOf("later", "soon", "today"), emptyList(), emptyList(), "", emptySet(),
            followers = listOf(PersonalFollower("specific", 1, 1, 1, 3))))
    }

    private fun rank(
        neural: List<String>, fallback: List<String> = emptyList(), learned: List<Pair<String, Int>> = emptyList(),
        prefix: String = "", blocked: Set<String> = emptySet(), limit: Int = 3,
    ) = NeuralRanking.rank(neural, fallback, learned, prefix, blocked, limit)
}
