package dev.localtype.keyboard

import java.io.File
import kotlin.random.Random

/** Shared evaluation data for the corrector: real typos, simulated taps, and words that must be left alone. */
object TypoCorpus {
    val dictionary: WordDictionary by lazy {
        (File("src/main/assets/words_en.txt").readLines() + File("src/main/assets/words_en_modern.txt").readLines())
            .let { WordDictionary.parse(it.asSequence()) }
    }
    val lexicon: Lexicon by lazy {
        val modern = File("src/main/assets/words_en_modern.txt").readLines().map { it.substringBefore('\t') }
        Lexicon.parse(File("src/main/assets/words_known.txt").readLines().asSequence() + modern)
    }

    /** Typed → meant, collected from real fast typing on a phone and common misspellings. */
    val real: List<Pair<String, String>> = """
        cacthig catching|woklr would|teh the|adn and|taht that|wiht with|thier their|recieve receive
        becuase because|dont don't|whta what|jsut just|knwo know|waht what|yuo you|hwo how|tje the
        tge the|thw the|qnd and|wsnt wasn't|heer here|woudl would|shoudl should|goign going|realy really
        abotu about|lokking looking|nto not|fo of|ot to|ti it|becasue because|beleive believe|tommorow tomorrow
        freind friend|wierd weird|untill until|occured occurred|seperate separate|definately definitely
        acheive achieve|accomodate accommodate|goverment government|enviroment environment|probaly probably
        pleese please|thnaks thanks|thansk thanks|tahnks thanks|agian again|anyways anyway|somthing something
        evrything everything|evreyone everyone|peopel people|pepole people|thign thing|thigns things
        whne when|wehn when|tiem time|mroe more|soem some|hvae have|ahve have|nad and|yuor your|yoru your
        cna can|nwo now|konw know|liek like|lkie like|mkae make|amke make|todya today|tonihgt tonight
        tomorow tomorrow|yesterady yesterday|halp help|workign working|lookign looking|tryign trying
        gonig going|comign coming|wnat want|wnt want|dint didn't|didnt didn't|doesnt doesn't|isnt isn't
        couldnt couldn't|wouldnt wouldn't|shouldnt shouldn't|havent haven't|wasnt wasn't|youre you're
        theyre they're|thats that's|whats what's|lets let's|ive i've|id i'd|hes he's|shes she's
        mesage message|messgae message|meesage message|picutre picture|pictrue picture|phnoe phone|hpone phone
        recieved received|adress address|calender calendar|begining beginning|buisness business|bussiness business
        dinnr dinner|dinenr dinner|lunhc lunch|brekfast breakfast|breakfsat breakfast|coffe coffee|cofee coffee
        wrok work|owrk work|hoem home|hmoe home|mornign morning|moring morning|nigth night|nihgt night
        awesoem awesome|amazign amazing|beautful beautiful|beatiful beautiful|intersting interesting|importnat important
        diffrent different|differnt different|remeber remember|rember remember|forgto forgot|foget forget
        whcih which|wich which|becuz because|probabyl probably|actualy actually|acutally actually|basicaly basically
        """.trim().split(Regex("[|\\n]")).map { it.trim() }.filter { it.isNotEmpty() }.map {
        val (typed, meant) = it.split(' ')
        typed to meant
    }

    /** Real words, names and slang that are in neither list and must not be changed on space. */
    val guard: List<String> = """
        lol lmao idk tbh ngl imo smh fr rn tho bc omw irl wfh brb ttyl jk nvm pls plz thx ty np bday
        yeet vibe vibes vibing sus lowkey highkey bestie stan simp rizz cringe mood slay periodt bussin
        doomscroll zoomer boomer millennial selfie hashtag unfollow retweet livestream podcasting
        kubernetes javascript typescript kotlin github gradle webpack figma notion airtable jira
        steuber luka kiosk drummer geepers localtype craigslist venmo zelle doordash grubhub ubereats
        anyhoo gotcha kinda sorta dunno lemme gimme ya yall y'all ain't innit whatevs totes obvi
        """.trim().split(Regex("\\s+"))

    data class Sample(val typed: String, val meant: String, val touches: List<Touch>)

    /**
     * Words typed by a simulated thumb: every tap lands around the intended key with Gaussian
     * spread [spread] (in key units); a few letters are skipped or doubled. Only results that are not
     * real words are kept, since a real word is never corrected.
     */
    fun simulated(count: Int, spread: Float = 0.42f, seed: Int = 7): List<Sample> {
        val random = Random(seed)
        val pool = dictionary.allWords().filter { w ->
            w.length >= 3 && w.all { it in 'a'..'z' } && dictionary.score(w) >= 45
        }
        val samples = ArrayList<Sample>()
        while (samples.size < count) {
            val meant = pool[random.nextInt(pool.size)]
            val typed = StringBuilder()
            val touches = ArrayList<Touch>()
            for (c in meant) {
                val roll = random.nextFloat()
                if (roll < 0.02f) continue // skipped a key
                val repeat = if (roll > 0.98f) 2 else 1
                repeat(repeat) {
                    val touch = KeyGeometry.touchOn(c, gaussian(random) * spread, gaussian(random) * spread)!!
                    val clamped = Touch(touch.x.coerceIn(0f, 10f), touch.y.coerceIn(0f, 2.99f))
                    touches += clamped
                    typed.append(KeyGeometry.nearest(clamped))
                }
            }
            val word = typed.toString()
            if (word == meant || word.length < 2 || word in dictionary || word in lexicon) continue
            samples += Sample(word, meant, touches)
        }
        return samples
    }

    private fun gaussian(random: Random): Float {
        val u = random.nextDouble().coerceAtLeast(1e-9)
        val v = random.nextDouble()
        return (kotlin.math.sqrt(-2 * kotlin.math.ln(u)) * kotlin.math.cos(2 * Math.PI * v)).toFloat()
    }
}
