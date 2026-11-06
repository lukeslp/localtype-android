package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.StringReader

class PredictionLogCodecTest {
    private val codec = PredictionLogCodec

    @Test
    fun `personal learning identity freezes incompatible append while legacy remains readable`() {
        val legacy = codec.priorHeader(null, AdaptiveNgramModel.PriorWeights.DEFAULT)
        val current = legacy + ("personalLearning" to NeuralRanking.PERSONAL_POLICY_ID)
        assertTrue(codec.sameRanking(legacy, legacy))
        assertTrue(codec.sameRanking(current, current))
        assertFalse(codec.sameRanking(legacy, current))
        assertFalse(codec.sameRanking(current, legacy))
        val owned = current + ("personalLearning" to WordPersistence.LOG_POLICY_ID)
        assertTrue(codec.sameRanking(owned, owned))
        assertFalse(codec.sameRanking(current, owned))
        assertFalse(codec.sameRanking(owned, current))
        assertFalse(codec.sameRanking(current, current + ("personalLearning" to "personal-learning-v2-context3-slots2")))
        val text = codec.encodeHeader(legacy) + "END\n"
        assertEquals(listOf(LogRecord.SnapshotEnd), codec.read(StringReader(text)).second.toList())
    }


    /** The spec's example file, TABs real; every record type appears at least once. */
    private val header = mapOf(
        "app" to "0.4.5",
        "code" to "15",
        "flavor" to "llm",
        "sdk" to "35",
        "file" to "1",
        "dict" to "20214,20390",
        "contextScope" to "verified-sentence",
        "contextChars" to "160",
        "keyChars" to "200",
        "candidates" to "6",
        "prior" to "1e7a8b0c".repeat(8),
        "priorWeights" to "20.0,-10.0,16.0",
        "seeds" to PredictionLogCodec.SHIPPED_SEEDS,
        "neural" to PredictionLogCodec.NEURAL_NONE,
        "correctionPolicy" to "test-v1",
        "autocorrect" to "1",
        "webAutocorrect" to "1",
        "addedWords" to AddedWords.VERSION,
        "vocabulary" to "none",
        "vocabularyPolicy" to CurrentVocabulary.POLICY,
        "newsBoost" to "0",
        "personalLearning" to NeuralRanking.PERSONAL_POLICY_ID,
    )
    private val lines = listOf(
        "SNAP\tprocess\t2",
        "U\tthanks\t3",
        "B\t\\s\tthanks\t3",
        "END",
        "DROP\t2",
        "F\t1\t0\t1\t1\t0\t1",
        "W\t1\t1\t\t0\t1\t\t0",
        "O\t1\t0\t\tL\tN\t1\ti:104,hey:101,thanks:100,yes:99,hi:98,the:96\tHey,I,Thanks\tRRR\t0\t20214\t412\t930",
        "O\t1\t1\tT\tL\tN\t1\tthanks:100,the:96,that:92,this:90,they:88,there:85\tThe,Thanks,That\tRRR\t0\t20214\t388\t812",
        "Y\t1\tThanks\tThanks\t \t0\t1\t1",
        "W\t2\t1\tThanks \t0\t0\tthanks\t0",
        "O\t2\t0\t\tL\tN\t1\tfor:140,so:131,again:96,a:95,to:94,you:92\tso,for,again\tRRR\t0\t20214\t301\t640",
        "T\t2\t0\t1\tfor\tR\t1",
        "X\t1\t\t",
        "A\t3\tN",
        "R\t2",
        "F\t2\t1\t1\t1\t2\t0",
        "O\t4\t2\tth\tS\tC\t0\t\t\"th\",the,this\tLCR\t1\t0\t-1\t-1",
        "Y\t4\thelo\thello\t.\t1\t1\t0",
        "SNAP\tsame\t0",
        "SNAP\tunlogged-partial\t50000",
        "EF\t1\t4\t0\t1\t0\t1\t0\t0\t1",
    )

    private fun records(text: String, stats: PredictionLogCodec.DecodeStats = PredictionLogCodec.DecodeStats()): List<LogRecord> =
        codec.read(StringReader(text), stats).second.toList()

    private fun file(vararg body: String) = codec.encodeHeader(header) + body.joinToString("") { it + "\n" }

    @Test
    fun `repair records round trip and malformed outcomes cannot claim learning`() {
        for (outcome in "OPJU") {
            val record = LogRecord.Repair(2, "teh", "the", outcome, outcome == 'P', AdaptiveNgramModel.START)
            assertEquals(record, codec.decode(codec.encode(record)))
        }
        for (line in listOf("FIX\t0\tteh\tthe\tP\t1\t", "FIX\t1\tteh\tthe\tQ\t0\t",
            "FIX\t1\tteh\tthe\tO\t1\t", "FIX\t1\tteh\tthe\tU\t1\t", "FIX\t1\tteh\tsecret 123\tP\t1\t",
            "FIX\t1\tteh\tthe\tP\t1\tbad context", "FIX\t1\tteh\tthe\tP\t2\t")) {
            val stats = PredictionLogCodec.DecodeStats()
            assertNull(line, codec.decode(line, stats))
            assertEquals(1, stats.malformed)
        }
    }

    @Test
    fun `correction compatibility distinguishes legacy policy and each setting while retaining prior keys`() {
        val prior = codec.priorHeader("ab".repeat(32), AdaptiveNgramModel.PriorWeights.DEFAULT)
        val neural = codec.neuralHeader("cd".repeat(32))
        val legacy = prior + neural
        val current = legacy + codec.correctionHeader("context-v1", true, true)
        assertTrue(codec.sameRanking(legacy, legacy))
        assertTrue(codec.sameRanking(current, current))
        assertFalse(codec.sameRanking(legacy, current))
        assertFalse(codec.sameRanking(current, legacy))
        for (changed in listOf(codec.correctionHeader("context-v2", true, true),
            codec.correctionHeader("context-v1", false, true), codec.correctionHeader("context-v1", true, false))) {
            assertFalse(codec.sameRanking(current, legacy + changed))
        }
        assertEquals(prior["seeds"], current["seeds"])
        assertEquals(neural["neural"], current["neural"])
        assertThrows(IllegalArgumentException::class.java) { codec.correctionHeader("bad\npolicy", true, true) }
        assertFalse(codec.sameRanking(current, current + ("addedWords" to AddedWords.VERSION)))
        val withAddedWords = current + ("addedWords" to AddedWords.VERSION)
        assertTrue(codec.sameRanking(withAddedWords, withAddedWords))
        assertFalse(codec.sameRanking(withAddedWords, current + ("addedWords" to "added-words-v2")))
    }

    @Test
    fun `escape round-trips every special character and leaves the rest alone`() {
        val samples = listOf(
            "", "plain", "tab\there", "line\nbreak", "return\rhere", "back\\slash", "comma,here",
            "start\u0002word", "\\t is not a tab", "\\\\", "emoji 😀🧑‍💻 done",
            "‘curly’ “quotes”", "all\t\n\r\\,\u0002at once", ",,,", "\\", "\\s\\n",
        )
        for (s in samples) {
            val escaped = codec.escape(s)
            // A comma stays a comma after its backslash; the others become letters.
            assertTrue("escaped form of $s holds a raw special", escaped.none { it == '\t' || it == '\n' || it == '\r' || it == '\u0002' })
            assertEquals(s, codec.unescape(escaped))
        }
        assertEquals("plain", codec.escape("plain"))
        assertEquals("\\s", codec.escape(AdaptiveNgramModel.START))
    }

    @Test
    fun `unescape rejects a dangling backslash and an unknown escape`() {
        assertNull(codec.unescape("trailing\\"))
        assertNull(codec.unescape("bad\\x"))
        assertEquals("ok", codec.unescape("ok"))
    }

    @Test
    fun `lists keep empty elements and odd characters`() {
        val slots = listOf("", "a,b", "", "tab\tbed", "😀")
        val offer = LogRecord.Offer(1, 0, "", 'L', 'N', true, emptyList(), slots, "BRBRE", 0, 0, 0, 0)
        val decoded = codec.decode(codec.encode(offer)) as LogRecord.Offer
        assertEquals(slots, decoded.slots)
        assertEquals(emptyList<Pair<String, Int>>(), decoded.ranked)
        // Three empty slots are two commas; an empty field is an empty list.
        assertEquals(listOf("", "", ""), (codec.decode("O\t1\t0\t\tL\tN\t1\t\t,,\tBBB\t0\t0\t0\t0") as LogRecord.Offer).slots)
        assertEquals(emptyList<String>(), (codec.decode("O\t1\t0\t\tL\tN\t1\t\t\tBBB\t0\t0\t0\t0") as LogRecord.Offer).slots)
    }

    @Test
    fun `encode of decode reproduces every example line byte for byte`() {
        val seen = HashSet<String>()
        for (line in lines) {
            val record = codec.decode(line)
            assertTrue("decodes: $line", record != null)
            assertEquals(line, codec.encode(record!!))
            seen += record.tag
        }
        assertEquals(setOf("SNAP", "U", "B", "END", "DROP", "F", "W", "O", "T", "Y", "A", "R", "X", "EF"), seen)
    }

    @Test
    fun `decode of encode reproduces every record type`() {
        val all = listOf(
            LogRecord.Snapshot("process", 2),
            LogRecord.Snapshot("same", 0),
            LogRecord.Snapshot("file-partial", 50_000),
            LogRecord.Unigram("thanks", 3),
            LogRecord.Bigram(AdaptiveNgramModel.START, "thanks", 3),
            LogRecord.Bigram("don't", "‘odd’", 1),
            LogRecord.SnapshotEnd,
            LogRecord.Dropped(4),
            LogRecord.Field(7, restart = true, autocorrect = false, profanity = true, model = 2, emoji = false),
            LogRecord.Word(12, 7, "thanks for the ", cut = true, ss = false, prev = "the", revisit = true),
            LogRecord.Offer(
                12, 3, "hel", 'L', 'N', false,
                listOf("hello" to 140, "help" to 96, "he'll" to -1),
                listOf("help", "hello", "😀"),
                "RRE", 1, 20214, 412, 930,
            ),
            LogRecord.Tap(12, 3, 1, "hello", 'R', ps = false),
            LogRecord.Typed(13, "helo", "hello", '\t', corrected = true, learned = true, ps = true),
            LogRecord.Typed(14, "hi", "hi", '\n', corrected = false, learned = false, ps = false),
            LogRecord.Abandon(15, 'F'),
            LogRecord.Revisit(12),
            LogRecord.Mutation(7, "", ""),
            LogRecord.Mutation(7, "word", ""),
            LogRecord.Mutation(7, "word", "prev"),
            LogRecord.FieldEnd(7, 1, 2, 3, 4, 5, 6, 7, 8),
        )
        for (r in all) {
            val line = codec.encode(r)
            assertTrue("one line: $line", '\n' !in line && '\r' !in line)
            assertEquals(r, codec.decode(line))
        }
        assertTrue((all[2] as LogRecord.Snapshot).partial)
        assertTrue((all[1] as LogRecord.Snapshot).same)
    }

    @Test
    fun `malformed and unknown lines return null and are counted`() {
        val stats = PredictionLogCodec.DecodeStats()
        val malformed = listOf(
            "",
            "T\t2\t0\t1\tfor\tR", // one field short
            "T\t2\t0\t1\tfor\tR\t1\textra",
            "T\t2\t0\t1\tfor\tRM\t1", // two-character char
            "T\t2\t0\t1\tfor\tR\t2", // boolean out of range
            "T\t02\t0\t1\tfor\tR\t1", // non-canonical int
            "T\t+2\t0\t1\tfor\tR\t1",
            "T\tx\t0\t1\tfor\tR\t1",
            "W\t1\t1\tbad\\q\t0\t1\t\t0", // unknown escape
            "W\t1\t1\tbad\\\t0\t1\t\t0", // dangling backslash
            "O\t1\t0\t\tL\tN\t1\tnocolon\t,,\tBBB\t0\t0\t0\t0",
            "O\t1\t0\t\tL\tN\t1\they:1.5\t,,\tBBB\t0\t0\t0\t0",
            "END\t1",
            "SNAP\tprocess",
        )
        for (line in malformed) assertNull("malformed: $line", codec.decode(line, stats))
        assertEquals(malformed.size, stats.malformed)
        assertEquals(0, stats.unknown)

        val unknown = listOf("Z\t1\t2", "#app=0.4.5", "snap\tprocess\t2", "OFFER\t1")
        for (line in unknown) assertNull("unknown: $line", codec.decode(line, stats))
        assertEquals(unknown.size, stats.unknown)
        assertEquals(malformed.size + unknown.size, stats.total)
    }

    @Test
    fun `header round-trips with the magic line first`() {
        val text = codec.encodeHeader(header)
        val headerLines = text.split('\n').filter { it.isNotEmpty() }
        assertEquals("#localtype-log=1", headerLines.first())
        assertEquals(header.size + 1, headerLines.size)
        val parsed = headerLines.associate { codec.parseHeader(it)!! }
        assertEquals(header + (PredictionLogCodec.MAGIC_KEY to "1"), parsed)
        assertEquals("20214,20390", parsed["dict"])
        assertEquals(PredictionLogCodec.HEADER_KEYS, header.keys.toList())

        assertNull(codec.parseHeader("T\t2\t0\t1\tfor\tR\t1"))
        assertNull(codec.parseHeader("#nokey"))
        assertNull(codec.parseHeader("#=value"))
        assertEquals("k" to "a=b", codec.parseHeader("#k=a=b"))
        assertEquals("k" to "", codec.parseHeader("#k="))

        // A magic entry in the map is ignored: the codec writes its own version.
        assertEquals(text, codec.encodeHeader(mapOf(PredictionLogCodec.MAGIC_KEY to "9") + header))
        assertThrows(IllegalArgumentException::class.java) { codec.encodeHeader(mapOf("a=b" to "1")) }
        assertThrows(IllegalArgumentException::class.java) { codec.encodeHeader(mapOf("a" to "two\nlines")) }
    }

    @Test
    fun `the prior header names the digest and weights and compares files by them`() {
        val weights = AdaptiveNgramModel.PriorWeights.DEFAULT
        val digest = "ab".repeat(32)
        val current = codec.priorHeader(digest, weights)
        assertEquals(listOf("prior", "priorWeights", "seeds"), current.keys.toList())
        assertEquals(PredictionLogCodec.HEADER_KEYS.filter { it in setOf("prior", "priorWeights", "seeds") }, current.keys.toList())
        assertEquals(PredictionLogCodec.SHIPPED_SEEDS, current["seeds"])
        assertEquals(digest, current["prior"])
        assertEquals("20.0,-10.0,16.0", current["priorWeights"])
        assertEquals(digest, codec.priorDigest(current))
        assertEquals(weights, codec.priorWeights(current))
        assertEquals(AdaptiveNgramModel.PriorWeights(2.5f, -8f, 4f), codec.priorWeights(codec.priorHeader(digest, AdaptiveNgramModel.PriorWeights(2.5f, -8f, 4f))))

        val none = codec.priorHeader(null, weights)
        assertEquals(PredictionLogCodec.PRIOR_NONE, none["prior"])
        assertNull(codec.priorDigest(none))
        // A file from before the keys existed was ranked with no prior, at the default weights.
        assertNull(codec.priorDigest(header - "prior" - "priorWeights"))
        assertEquals(weights, codec.priorWeights(header - "prior" - "priorWeights"))
        assertNull(codec.priorWeights(mapOf("priorWeights" to "20,-10")))
        assertNull(codec.priorWeights(mapOf("priorWeights" to "20,10,16")))
        assertNull(codec.priorWeights(mapOf("priorWeights" to "a,b,c")))

        assertTrue(codec.samePrior(header + current, current))
        assertTrue(codec.samePrior(header - "prior" - "priorWeights" - "seeds", none))
        assertTrue("seeds don't matter without a prior", codec.samePrior(none, codec.priorHeader(null, weights, "ef".repeat(32))))
        assertFalse("other seeds under a prior", codec.samePrior(header + current, codec.priorHeader(digest, weights, "ef".repeat(32))))
        assertFalse("a file from before #seeds under a prior", codec.samePrior(header + current - "seeds", current))
        assertTrue("weights don't matter without a prior", codec.samePrior(none, codec.priorHeader(null, AdaptiveNgramModel.PriorWeights(5f))))
        assertFalse("an old file under a build with the prior", codec.samePrior(header - "prior" - "priorWeights" - "seeds", current))
        assertFalse(codec.samePrior(header + current, none))
        assertFalse(codec.samePrior(header + current, codec.priorHeader("cd".repeat(32), weights)))
        assertFalse(codec.samePrior(header + current, codec.priorHeader(digest, AdaptiveNgramModel.PriorWeights(40f))))
        assertFalse("an unreadable header", codec.samePrior(emptyMap(), current))
    }

    @Test
    fun `neural header mode and field digest round trip without changing off records`() {
        val digest = "ab".repeat(32)
        val off = LogRecord.Field(1, false, true, false, 0, true)
        assertEquals("F\t1\t0\t1\t0\t0\t1", codec.encode(off))
        assertEquals(off, codec.decode(codec.encode(off)))
        val on = off.copy(neuralSha256 = digest)
        assertEquals(codec.encode(off) + "\t" + digest, codec.encode(on))
        assertEquals(on, codec.decode(codec.encode(on)))
        assertNull(codec.decode(codec.encode(off) + "\tnone"))
        assertNull(codec.decode(codec.encode(off) + "\t" + digest.uppercase()))

        val current = codec.priorHeader(null, AdaptiveNgramModel.PriorWeights.DEFAULT)
        val none = current + codec.neuralHeader(null)
        val neural = current + codec.neuralHeader(digest)
        assertEquals("#neural=none\n", codec.encodeHeader(codec.neuralHeader(null)).substringAfter('\n'))
        assertEquals(digest, codec.neuralDigest(codec.readHeader(StringReader(codec.encodeHeader(neural)))))
        assertNull(codec.neuralDigest(emptyMap()))
        assertTrue(codec.sameRanking(current, none))
        assertFalse(codec.sameRanking(none, neural))
        assertFalse(codec.sameRanking(neural, none))
        assertFalse(codec.sameRanking(neural, current + codec.neuralHeader("cd".repeat(32))))
        assertTrue(codec.sameRanking(neural, neural))
        assertThrows(IllegalArgumentException::class.java) { codec.neuralHeader("bad") }
    }

    @Test
    fun `neural and protected personal sources round trip with unchanged ranked list`() {
        val offer = LogRecord.Offer(1, 0, "", 'L', 'N', true, listOf("the" to 100), listOf("thanks", "folks", "😀"), "NPE", 0, 20, 30, 40)
        assertEquals(offer, codec.decode(codec.encode(offer)))
        for (src in listOf('N', 'P')) {
            val tap = LogRecord.Tap(1, 0, 0, "thanks", src, true)
            assertEquals(tap, codec.decode(codec.encode(tap)))
        }
    }

    @Test
    fun `the seeds digest names the lists, their order and which list each line is in`() {
        val seeds = listOf("thank you very much", "see you soon")
        val starters = listOf("I", "Hey")
        val digest = codec.seedsDigest(seeds, starters)
        assertEquals(64, digest.length)
        assertEquals(digest, codec.seedsDigest(seeds.toList(), starters.toList()))
        assertEquals(codec.seedsDigest(AdaptiveNgramModel.DEFAULT_SEED_PHRASES, AdaptiveNgramModel.DEFAULT_SENTENCE_STARTERS), codec.SHIPPED_SEEDS)
        val others = listOf(
            codec.seedsDigest(seeds + "good night", starters),
            codec.seedsDigest(seeds, starters + "Ok"),
            codec.seedsDigest(seeds.reversed(), starters),
            codec.seedsDigest(seeds + "I", listOf("Hey")),
            codec.seedsDigest(seeds, emptyList()),
            codec.seedsDigest(emptyList(), seeds),
        )
        assertEquals(others.size + 1, (others + digest).toSet().size)
        assertThrows(IllegalArgumentException::class.java) { codec.seedsDigest(listOf("two\nlines"), starters) }
    }

    @Test
    fun `readHeader stops at the first record`() {
        val text = file(*lines.toTypedArray())
        assertEquals(header + (PredictionLogCodec.MAGIC_KEY to "1"), codec.readHeader(StringReader(text)))
        assertEquals(emptyMap<String, String>(), codec.readHeader(StringReader("SNAP\tfile\t0\n#app=late\n")))
    }

    @Test
    fun `read returns the header and the records and counts what it skipped`() {
        val stats = PredictionLogCodec.DecodeStats()
        val (parsed, records) = codec.read(StringReader(file(*lines.toTypedArray(), "Z\t9", "T\tbroken")), stats)
        assertEquals("1", parsed[PredictionLogCodec.MAGIC_KEY])
        assertEquals("0.4.5", parsed["app"])
        assertEquals(header.size + 1, parsed.size)
        // The sequence is lazy: nothing has been counted until it is consumed.
        assertEquals(0, stats.total)
        val list = records.toList()
        assertEquals(lines.size, list.size)
        assertEquals(lines, list.map { codec.encode(it) })
        assertEquals(1, stats.unknown)
        assertEquals(1, stats.malformed)
        assertThrows(IllegalStateException::class.java) { records.toList() }
    }

    @Test
    fun `read refuses a file whose magic value is not 1`() {
        val bad = "#localtype-log=2\n#app=0.4.5\n" + lines.first() + "\n"
        val e = assertThrows(IllegalArgumentException::class.java) { codec.read(StringReader(bad)) }
        assertTrue(e.message!!.contains("#localtype-log=2"))
        assertThrows(IllegalArgumentException::class.java) { codec.read(StringReader("#app=0.4.5\n" + lines.first() + "\n")) }
        assertThrows(IllegalArgumentException::class.java) { codec.read(StringReader("")) }
        assertThrows(IllegalArgumentException::class.java) { codec.read(StringReader(lines.first() + "\n")) }
    }

    @Test
    fun `header lines without a value are counted malformed and the rest still reads`() {
        val stats = PredictionLogCodec.DecodeStats()
        val text = "#localtype-log=1\n#broken\n" + lines.first() + "\n"
        val (parsed, records) = codec.read(StringReader(text), stats)
        assertEquals(1, parsed.size)
        assertEquals(1, records.count())
        assertEquals(1, stats.malformed)
    }

    @Test
    fun `blockHash is stable, order-sensitive and hex`() {
        val rows = listOf("U\tthanks\t3", "B\t\\s\tthanks\t3")
        val hash = codec.blockHash(rows)
        assertEquals(64, hash.length)
        assertTrue(hash.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals(hash, codec.blockHash(rows.toList()))
        assertNotEquals(hash, codec.blockHash(rows.reversed()))
        assertNotEquals(hash, codec.blockHash(rows + "END"))
        // Known SHA-256 vectors: no rows hashes nothing; one row hashes the row plus LF.
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", codec.blockHash(emptyList()))
        assertEquals("edeaaff3f1774ad2888673770c6d64097e391bc362d7d6fb34982ddf0efd18cb", codec.blockHash(listOf("abc")))
        assertEquals("86a793eacf34013db3d6de1043dbaa01722b07a67c791cb8fa602cd61f7223e9", hash)
    }

    /** What the harness will count: a SNAP with no END is torn, a W with no T/Y/A is truncated. */
    private fun tornAndTruncated(records: List<LogRecord>): Pair<Int, Int> {
        var torn = 0
        var truncated = 0
        var inSnap = false
        var openWord = false
        for (r in records) {
            when (r) {
                is LogRecord.Snapshot -> {
                    if (inSnap) torn++
                    inSnap = !r.same
                }
                LogRecord.SnapshotEnd -> inSnap = false
                is LogRecord.Word -> {
                    if (openWord) truncated++
                    openWord = true
                }
                is LogRecord.Tap, is LogRecord.Typed, is LogRecord.Abandon -> openWord = false
                else -> Unit
            }
        }
        if (inSnap) torn++
        if (openWord) truncated++
        return torn to truncated
    }

    @Test
    fun `a file cut mid-SNAP parses cleanly and shows one torn block`() {
        val stats = PredictionLogCodec.DecodeStats()
        val cut = records(file("SNAP\tprocess\t2", "U\tthanks\t3"), stats)
        assertEquals(0, stats.total)
        assertEquals(2, cut.size)
        assertEquals(1 to 0, tornAndTruncated(cut))
        assertEquals(0 to 0, tornAndTruncated(records(file(*lines.take(4).toTypedArray()))))
    }

    @Test
    fun `a file cut mid-W parses cleanly and shows one truncated block`() {
        val stats = PredictionLogCodec.DecodeStats()
        // Through the second W and its O, then the T that would close it.
        val whole = lines.take(13)
        val cut = records(file(*whole.dropLast(1).toTypedArray()), stats)
        assertEquals(0, stats.total)
        assertEquals(0 to 1, tornAndTruncated(cut))
        assertEquals(0 to 0, tornAndTruncated(records(file(*whole.toTypedArray()))))
    }

    @Test
    fun `a file cut mid-line loses only that line`() {
        val stats = PredictionLogCodec.DecodeStats()
        val text = file(*lines.take(9).toTypedArray()).dropLast(20)
        val cut = records(text, stats)
        assertEquals(8, cut.size)
        assertEquals(1, stats.malformed)
    }

    @Test
    fun `a file belongs to the build that started it`() {
        assertTrue(codec.sameBuild(mapOf("code" to "37"), 37))
        assertFalse(codec.sameBuild(mapOf("code" to "37"), 38))
        assertFalse(codec.sameBuild(mapOf("code" to "037"), 37))
        assertFalse(codec.sameBuild(emptyMap(), 37))
    }

    @Test
    fun `the checked-in fixture parses with no malformed or unknown lines`() {
        val fixture = File("src/test/fixtures/prediction_log_sample.tsv")
        assumeTrue("fixture not generated yet", fixture.exists())
        val stats = PredictionLogCodec.DecodeStats()
        val (parsed, records) = fixture.bufferedReader().use { reader ->
            val (h, seq) = codec.read(reader, stats)
            h to seq.toList()
        }
        assertEquals("1", parsed[PredictionLogCodec.MAGIC_KEY])
        assertTrue(records.size > 100)
        assertEquals(0, stats.malformed)
        assertEquals(0, stats.unknown)
        assertEquals(0 to 0, tornAndTruncated(records))
        for (r in records) assertEquals(r, codec.decode(codec.encode(r)))
    }
}
