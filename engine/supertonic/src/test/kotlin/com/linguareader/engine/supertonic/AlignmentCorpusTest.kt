package com.linguareader.engine.supertonic

import com.linguareader.core.audio.SynthesisOutcome
import com.linguareader.core.content.BookManifestParser
import com.linguareader.core.engine.SpeechRequest
import com.linguareader.core.text.Tokenizer
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Genera el corpus para comparar alineadores forzados (tools/eval_aligners.py):
 *  - natural/: frases reales de los libros demo sintetizadas con Supertonic (sin verdad de referencia).
 *  - reference/: frases construidas concatenando palabras sintetizadas por separado con huecos
 *    conocidos → tiempos de palabra exactos (verdad de referencia, prosodia menos natural).
 * Solo se ejecuta con -Dsupertonic.alignCorpus=true (tarea :engine:supertonic:alignCorpus).
 */
class AlignmentCorpusTest {

    private val modelDir = File(System.getProperty("supertonic.modelDir", ".models/none"))
    private val out = File(System.getProperty("supertonic.corpusDir", "build/align-corpus"))

    @Test
    fun generateCorpus() = runBlocking {
        assumeTrue(System.getProperty("supertonic.alignCorpus") == "true")
        assumeTrue(SupertonicModelDir(modelDir).isComplete())
        val engine = SupertonicEngine(modelDir = { modelDir }, log = { println("[supertonic] $it") })
        try {
            File(out, "natural").deleteRecursively(); File(out, "reference").deleteRecursively()
            File(out, "natural").mkdirs()
            File(out, "reference").mkdirs()
            val manifest = StringBuilder("[\n")
            var first = true
            fun add(json: String) { if (!first) manifest.append(",\n"); manifest.append(json); first = false }

            // 1) Frases naturales de los libros (primer capítulo de cada uno).
            val books = listOf("en-a1-the-red-kite", "fr-a2-le-marche-du-samedi").map {
                BookManifestParser.parse(File("../../app/src/main/assets/books/$it/manifest.json").readText())
            }
            for (book in books) {
                val lang = book.learningLanguage
                for (passage in book.chapters.first().passages) for (s in passage.sentences) {
                    val id = "${lang}_${passage.id}_${s.id}"
                    val wav = File(out, "natural/$id.wav")
                    val r = engine.synthesizeTo(SpeechRequest(s.text, lang, "F1", 1.0f), wav)
                    check(r is SynthesisOutcome.Written)
                    val words = Tokenizer.tokenize(s.text, lang).map { s.text.substring(it.start, it.end) }
                    add("""  {"id":"$id","kind":"natural","lang":"$lang","wav":"natural/$id.wav","text":${json(s.text)},"words":${jsonList(words)}}""")
                }
            }

            // 2) Frases con verdad de referencia: palabra a palabra con huecos conocidos.
            val referenceSentences = listOf(
                "en" to "Tom wakes up early and looks out of the window",
                "en" to "The red kite flies away over the trees",
                "en" to "They walk to the beach together",
                "fr" to "Lila habite avec sa grand-mère près du marché",
                "fr" to "Elle cherche dans ses poches et dans le panier",
                "en" to "The old man sits near the river and reads a book",
                "en" to "My sister buys fresh bread at the market every morning",
                "en" to "We need more water because the day is very hot",
                "en" to "The children play in the garden after school",
                "en" to "She opens the door and sees a small brown dog",
                "en" to "Please tell me where I can find the train station",
                "en" to "He drinks a cup of hot tea before he goes to work",
                "en" to "Our teacher gives us a lot of homework on Fridays",
                "en" to "The shop closes at six so we must hurry",
                "en" to "I think it will rain tomorrow so take an umbrella",
                "en" to "They are building a new bridge across the valley",
                "en" to "Last summer we visited my grandparents in the mountains",
                "en" to "Can you help me carry these heavy bags to the car",
                "en" to "The soup was too salty but everyone ate it anyway",
                "en" to "A cold wind blew through the empty street at night",
                "en" to "She has lived in this small town for twenty years",
                "en" to "Do not forget to turn off the lights when you leave",
                "en" to "The museum is open every day except on Mondays",
                "en" to "He quickly wrote a letter to his friend in London",
                "en" to "We listened to the music until the sun came up",
                "en" to "The boy lost his keys somewhere between the school and home",
                "en" to "It is easy to learn a language when you read every day",
                "en" to "Their house has a red roof and a large blue gate",
                "en" to "After lunch they walked slowly along the quiet beach",
                "en" to "The doctor said that I should sleep more and drink water",
                "en" to "Nobody knew why the old clock stopped at midnight",
                "en" to "She smiled because the news was better than expected",
                "en" to "Bring your passport and two photographs to the office",
                "en" to "The farmer feeds the horses early in the cold morning",
                "en" to "We found a lovely little restaurant behind the church",
                "fr" to "Le vieux monsieur lit un livre près de la rivière",
                "fr" to "Ma soeur achète du pain frais au marché chaque matin",
                "fr" to "Nous avons besoin de plus d'eau parce qu'il fait très chaud",
                "fr" to "Les enfants jouent dans le jardin après l'école",
                "fr" to "Elle ouvre la porte et voit un petit chien brun",
                "fr" to "Pouvez-vous me dire où se trouve la gare s'il vous plaît",
                "fr" to "Il boit une tasse de thé chaud avant d'aller travailler",
                "fr" to "Notre professeur nous donne beaucoup de devoirs le vendredi",
                "fr" to "Le magasin ferme à six heures donc il faut se dépêcher",
                "fr" to "Je pense qu'il va pleuvoir demain alors prends un parapluie",
                "fr" to "Ils construisent un nouveau pont au-dessus de la vallée",
                "fr" to "L'été dernier nous avons rendu visite à mes grands-parents",
                "fr" to "Peux-tu m'aider à porter ces sacs lourds jusqu'à la voiture",
                "fr" to "La soupe était trop salée mais tout le monde l'a mangée",
                "fr" to "Un vent froid soufflait dans la rue vide pendant la nuit",
                "fr" to "Elle habite dans cette petite ville depuis vingt ans",
                "fr" to "N'oublie pas d'éteindre les lumières quand tu pars",
                "fr" to "Le musée est ouvert tous les jours sauf le lundi",
                "fr" to "Il a vite écrit une lettre à son ami à Paris",
                "fr" to "Nous avons écouté de la musique jusqu'au lever du soleil",
                "fr" to "Le garçon a perdu ses clés entre l'école et la maison",
                "fr" to "Il est facile d'apprendre une langue quand on lit chaque jour",
                "fr" to "Leur maison a un toit rouge et un grand portail bleu",
                "fr" to "Après le déjeuner ils ont marché lentement sur la plage calme",
                "fr" to "Le médecin a dit que je devrais dormir plus et boire de l'eau",
                "fr" to "Personne ne savait pourquoi la vieille horloge s'était arrêtée",
                "fr" to "Elle a souri parce que la nouvelle était meilleure que prévu",
                "fr" to "Apportez votre passeport et deux photographies au bureau",
                "fr" to "Le fermier donne à manger aux chevaux tôt le matin",
                "fr" to "Nous avons trouvé un joli petit restaurant derrière l'église",
            )
            val gapMs = 120
            referenceSentences.forEachIndexed { n, (lang, sentence) ->
                val words = sentence.split(" ")
                val pcm = ArrayList<Short>()
                val spans = mutableListOf<Pair<Double, Double>>()
                val sr = 44_100
                for ((i, w) in words.withIndex()) {
                    val tmp = File.createTempFile("word", ".wav")
                    check(engine.synthesizeTo(SpeechRequest(w, lang, if (n % 2 == 0) "M1" else "F1", 1.0f), tmp) is SynthesisOutcome.Written)
                    val samples = readPcm(tmp); tmp.delete()
                    if (i > 0) repeat(sr * gapMs / 1000) { pcm += 0 }
                    val start = pcm.size / sr.toDouble()
                    pcm.addAll(samples.toList())
                    spans += start to pcm.size / sr.toDouble()
                }
                val id = "ref_${lang}_$n"
                writePcm(File(out, "reference/$id.wav"), pcm.toShortArray(), sr)
                val ref = words.indices.joinToString(",") { """{"word":${json(words[it])},"start":${"%.3f".format(java.util.Locale.ROOT, spans[it].first)},"end":${"%.3f".format(java.util.Locale.ROOT, spans[it].second)}}""" }
                add("""  {"id":"$id","kind":"reference","lang":"$lang","wav":"reference/$id.wav","text":${json(sentence)},"words":${jsonList(words)},"truth":[$ref]}""")
            }
            manifest.append("\n]\n")
            File(out, "corpus.json").writeText(manifest.toString())
            println("Corpus en ${out.absolutePath}")
        } finally {
            engine.shutdown()
        }
    }

    private fun json(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    private fun jsonList(l: List<String>) = l.joinToString(",", "[", "]") { json(it) }

    private fun readPcm(f: File): ShortArray {
        val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val n = (f.length().toInt() - 44) / 2
        return ShortArray(n) { b.getShort(44 + it * 2) }
    }

    private fun writePcm(f: File, s: ShortArray, sr: Int) {
        WavWriter.write(f, FloatArray(s.size) { s[it] / 32767f }, sr)
    }
}
