// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.output

import app.heyrobot.core.output.HandsFreeCommand.CarModeOff
import app.heyrobot.core.output.HandsFreeCommand.CarModeOn
import app.heyrobot.core.output.HandsFreeCommand.ReadMessages
import app.heyrobot.core.output.HandsFreeCommand.Repeat
import app.heyrobot.core.output.HandsFreeCommand.StopListening
import app.heyrobot.core.output.HandsFreeCommand.WhatsNext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Headset and car mode by voice (design §4.3 phrases), anchored like the other routers, with misfires. */
class HandsFreeRouterTest {
    private fun r(s: String, car: Boolean = true) = HandsFreeRouter.route(s, car)

    @Test fun carModeOnAndOff() {
        for (s in listOf("car mode", "Car mode.", "turn on car mode", "Hey robot, start car mode", "please turn on car mode", "car mode on", "I'm driving",
                "i am driving", "driving mode", "mode auto", "Mode auto !", "active le mode auto", "démarre le mode auto", "je conduis", "Je conduis.",
                "peux-tu activer le mode auto", "mets le mode auto", "okay robot, car mode please", "passe en mode auto")) {
            assertEquals(s, CarModeOn, r(s, car = false))
            assertEquals(s, CarModeOn, r(s, car = true))
        }
        for (s in listOf("end car mode", "stop car mode", "turn off car mode", "car mode off", "exit car mode", "fin du mode auto", "arrête le mode auto",
                "désactive le mode auto", "hey robot, end car mode", "quitte le mode auto", "peux-tu arrêter le mode auto")) {
            assertEquals(s, CarModeOff, r(s, car = false))
            assertEquals(s, CarModeOff, r(s))
        }
    }

    @Test fun repeatAndStopListening() {
        for (s in listOf("repeat", "Repeat that.", "read that again", "say that again", "can you repeat that", "répète", "Répète ça", "répète-moi ça", "redis-le",
                "relis ça", "peux-tu répéter", "hey robot, repeat that please", "what did you say")) {
            assertEquals(s, Repeat, r(s, car = false))
        }
        for (s in listOf("stop listening", "Stop listening to me.", "that's all", "that's all for now", "I'm done", "arrête d'écouter", "c'est tout",
                "merci, c'est tout", "c'est tout merci", "thanks, that's all", "c'est beau", "j'ai fini")) {
            assertEquals(s, StopListening, r(s, car = false))
        }
    }

    @Test fun carModesOwnPhrasesOnlyInCarMode() {
        val car = mapOf(
            "navigate home" to HandsFreeCommand.Navigate(PlaceWord.HOME, "home"),
            "take me home" to HandsFreeCommand.Navigate(PlaceWord.HOME, "home"),
            "take me to work" to HandsFreeCommand.Navigate(PlaceWord.WORK, "work"),
            "Navigate to the office" to HandsFreeCommand.Navigate(PlaceWord.WORK, "work"),
            "amène-moi à la maison" to HandsFreeCommand.Navigate(PlaceWord.HOME, "home"),
            "emmène-moi chez moi" to HandsFreeCommand.Navigate(PlaceWord.HOME, "home"),
            "itinéraire vers le travail" to HandsFreeCommand.Navigate(PlaceWord.WORK, "work"),
            "amène-moi au bureau" to HandsFreeCommand.Navigate(PlaceWord.WORK, "work"),
            "directions to the nearest gas station" to HandsFreeCommand.Navigate(PlaceWord.NEAREST, "gas station"),
            "the nearest pharmacy" to HandsFreeCommand.Navigate(PlaceWord.NEAREST, "pharmacy"),
            "le poste d'essence le plus proche" to HandsFreeCommand.Navigate(PlaceWord.NEAREST, "poste d'essence"),
            "amène-moi à la pharmacie la plus proche" to HandsFreeCommand.Navigate(PlaceWord.NEAREST, "pharmacie"),
            "navigate to 1234 Rue Sainte-Catherine" to HandsFreeCommand.Navigate(PlaceWord.OTHER, "1234 Rue Sainte-Catherine"),
            "take me to the airport" to HandsFreeCommand.Navigate(PlaceWord.OTHER, "the airport"),
            "call Mom" to HandsFreeCommand.Call("Mom"),
            "Call Marie on her cell" to HandsFreeCommand.Call("Marie", "mobile"),
            "call Marie on her cell phone" to HandsFreeCommand.Call("Marie", "mobile"),
            "call Jean-François at work" to HandsFreeCommand.Call("Jean-François", "work"),
            "appelle maman" to HandsFreeCommand.Call("maman"),
            "appelle Marie sur son cell" to HandsFreeCommand.Call("Marie", "mobile"),
            "appelle Marie à la maison" to HandsFreeCommand.Call("Marie", "home"),
            "hey robot, call 911" to HandsFreeCommand.Call("911"),
            "dial 514 555 0101" to HandsFreeCommand.Call("514 555 0101"),
            "call Marie Martin please" to HandsFreeCommand.Call("Marie Martin"),
            "read my messages" to ReadMessages, "any messages?" to ReadMessages, "do I have any new messages" to ReadMessages,
            "lis-moi mes messages" to ReadMessages, "j'ai-tu des messages?" to ReadMessages, "est-ce que j'ai des messages" to ReadMessages,
            "what's next" to WhatsNext, "What's my next meeting?" to WhatsNext, "my next appointment" to WhatsNext, "c'est quoi la suite?" to WhatsNext,
            "mon prochain rendez-vous" to WhatsNext,
            "play music" to HandsFreeCommand.Music(MusicAction.PLAY), "pause the music" to HandsFreeCommand.Music(MusicAction.PAUSE),
            "next song" to HandsFreeCommand.Music(MusicAction.NEXT), "toune suivante" to HandsFreeCommand.Music(MusicAction.NEXT),
            "mets de la musique" to HandsFreeCommand.Music(MusicAction.PLAY), "chanson précédente" to HandsFreeCommand.Music(MusicAction.PREVIOUS),
            "pause" to HandsFreeCommand.Music(MusicAction.PAUSE),
            "tell her I'm 10 minutes away" to HandsFreeCommand.Reply("I'm 10 minutes away"),
            "reply I'm on my way" to HandsFreeCommand.Reply("I'm on my way"),
            "réponds-lui que j'arrive" to HandsFreeCommand.Reply("j'arrive"),
            "dis-lui que je suis en chemin" to HandsFreeCommand.Reply("je suis en chemin"),
        )
        for ((s, c) in car) {
            assertEquals(s, c, r(s))
            assertNull(s, r(s, car = false))
        }
    }

    @Test fun misfires() {
        for (s in listOf("", "hey robot", "please", "what's the car mode in Android", "my car mode button is broken", "I'm driving to Montreal tomorrow, what's the weather there",
                "is car mode on", "repeat after me hello", "stop listening to that song", "le mode auto de ma voiture est brisé", "je conduis demain à Québec",
                "what's the weather", "set a timer for 5 minutes", "what's next week's forecast", "call me later", "call it a day", "call off the meeting", "call back",
                "what do you call a baby kangaroo", "call of duty", "call her", "tell me a joke", "tell him", "reply", "where is the nearest pharmacy",
                "navigate", "take me", "pause for a second and think", "play", "next", "messages", "c'est tout ce que tu sais?", "that's all you know?",
                "my music is too loud", "the nearest", "le plus proche")) {
            assertNull(s, r(s))
        }
        // A wake phrase of your own in front still counts.
        assertEquals(CarModeOn, HandsFreeRouter.route("computer, car mode", carMode = false, wakePhrases = listOf("computer")))
    }
}
