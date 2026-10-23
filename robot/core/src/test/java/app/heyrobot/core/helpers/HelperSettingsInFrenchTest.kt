// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.helpers

import app.heyrobot.core.HelperEffortSetting
import app.heyrobot.core.AnthropicModel
import app.heyrobot.core.AskRoute
import app.heyrobot.core.HelperVoices
import app.heyrobot.core.Escalation
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import app.heyrobot.core.detail
import app.heyrobot.core.title

/**
 * 1.1 (the l10n design's WP2b, its second part): the words core gives Settings › Helpers and a helper's connection
 * window (the presets, Claude's models, effort, voices and routes, when Robot needs help, the name and key rules, the
 * address hints, the price table's date and the amounts), in Québec French, and 1.0's English unchanged.
 */
class HelperSettingsInFrenchTest {
    private val fr = Langs.of(Lang.FR)
    private val nb = ' '
    private val fine = ' '
    private val wake = setOf("hey", "robot")
    private val jarvis = HelperConfig.of(Presets.HOME, "hjar", "am_puck", name = "Jarvis", baseUrl = "192.168.1.20")
    private val s = RobotSettings().withHelper(jarvis)

    @Test fun theEnglishIsOnePointZeros() {
        assertEquals("Recommended for most questions: \$4 per million tokens in, \$20 out.", AnthropicModel.OPUS.detail)
        assertEquals("Automatic", HelperEffortSetting.AUTO.title)
        assertEquals("Emma (British, female)", HelperVoices.title("bf_emma"))
        assertEquals("am_unknown", HelperVoices.title("am_unknown"))
        assertEquals("Claude app", AskRoute.APP.title)
        assertEquals("Robot asks Claude itself and speaks the answer. Needs an API key with credits.", AskRoute.API.detail)
        assertEquals("Ask me first", Escalation.ASK.title)
        assertEquals("Ask first", Escalation.ASK.labelText.en())
        assertEquals("Robot never suggests a helper.", Escalation.OFF.detail)
        assertEquals("OpenRouter (hundreds of models)", Presets.OPENROUTER.title)
        assertEquals("OpenAI (GPT)", Presets.OPENAI.title)
        assertEquals("Your own server", Presets.HOME.company)
        assertEquals("Mistral AI", Presets.MISTRAL.company)
        assertEquals("API use is paid separately from a ChatGPT subscription.", Presets.OPENAI.billing)
        assertEquals("DeepSeek's API has no web search.", Presets.DEEPSEEK.searchNote)
        assertNull(Presets.ANTHROPIC.searchNote)
        assertEquals("\$0.80 in, \$4 out; can see pictures.", Presets.GROQ.model("qwen/qwen3.8-27b")!!.detail)
        assertEquals("“Max” is a common word or name: “ask Max …” will go to this helper, never to someone called Max.",
            (HelperNames.check("Max", "new", s, wake) as NameCheck.Warning).why)
        assertEquals("Jarvis is already called that.", (HelperNames.check("Jarvis", "new", s, wake) as NameCheck.Problem).why)
        assertEquals("“robot” is in a wake phrase: Robot would mix them up.", (HelperNames.check("Robot", "new", s, wake) as NameCheck.Problem).why)
        assertEquals("OpenAI keys usually start with sk-. Robot will try this one anyway.", HelperKeyFormat.warning(Presets.OPENAI, "abc12345"))
        assertNull(HelperKeyFormat.warning(Presets.OPENAI, "sk-abc12345"))
        assertEquals("That doesn't look like an Anthropic API key (sk-ant- followed by letters, digits, - and _).", HelperKeyFormat.refusal(Presets.ANTHROPIC))
        assertEquals("This is a Tailscale name: it resolves only while Tailscale is on.", LanRule.normalize("box.tail1234.ts.net")!!.hint)
        assertEquals("That address isn't on your home network, so Robot won't send to it without https.", LanRule.NOT_LOCAL)
        assertEquals("cost unknown", Pricing.line(null))
        assertEquals("11 Oct 2026", Pricing.dateText(PriceTable.AS_OF).en())
        assertEquals("\$0.075", HelperFormat.perMillion(0.075, Lang.EN))
        assertEquals("\$12.50", HelperFormat.perMillion(12.5, Lang.EN))
        assertEquals("\$2", HelperFormat.perMillion(2.0, Lang.EN))
        assertEquals("\$0.42", HelperFormat.dollars(420_000, Lang.EN))
        assertEquals("\$5", HelperFormat.budget(5_000_000, Lang.EN))
        assertEquals("\$0.50", HelperFormat.budget(500_000, Lang.EN))
        assertEquals("0.5¢", HelperFormat.cents("0.5", Lang.EN))
        assertEquals("1.0", HelperFormat.oneDecimal(1.0, Lang.EN))
    }

    @Test fun inFrench() {
        assertEquals("Recommandé pour la plupart des questions$nb: 4$nb\$ US par million de jetons en entrée, 20$nb\$ US en sortie.",
            AnthropicModel.OPUS.detailText.render(fr))
        assertEquals("Élevé", HelperEffortSetting.HIGH.titleText.render(fr))
        assertEquals("Emma (britannique, femme)", HelperVoices.titleText("bf_emma").render(fr))
        assertEquals("Appli Claude", AskRoute.APP.titleText.render(fr))
        assertEquals("Ouvre l’appli Claude avec ta question$fine; utilise ton forfait claude.ai. Robot n’entend pas la réponse.", AskRoute.APP.detailText.render(fr))
        assertEquals("Demander d’abord", Escalation.ASK.labelText.render(fr))
        assertEquals("Me demander d’abord", Escalation.ASK.titleText.render(fr))
        assertEquals("OpenRouter (des centaines de modèles)", Presets.OPENROUTER.titleText.render(fr))
        assertEquals("OpenAI (GPT)", Presets.OPENAI.titleText.render(fr))
        assertEquals("Une autre entreprise", Presets.OTHER.companyText.render(fr))
        assertEquals("L’API de DeepSeek n’a pas de recherche web.", Presets.DEEPSEEK.searchNoteText!!.render(fr))
        assertEquals("0,80$nb\$ US en entrée, 4$nb\$ US en sortie$fine; voit les images.", Presets.GROQ.model("qwen/qwen3.8-27b")!!.detailText.render(fr))
        assertEquals("Jarvis porte déjà ce nom.", (HelperNames.check("Jarvis", "new", s, wake) as NameCheck.Problem).text.render(fr))
        assertEquals("«${nb}robot$nb» fait partie d’un de tes mots d’éveil$nb: Robot les confondrait.",
            (HelperNames.check("Robot", "new", s, wake) as NameCheck.Problem).text.render(fr))
        assertEquals("Donne-lui un nom d’un à trois mots.", (HelperNames.check("", "new", s, wake) as NameCheck.Problem).text.render(fr))
        assertEquals("Les clés OpenAI commencent habituellement par sk-. Robot va quand même essayer celle-ci.",
            HelperKeyFormat.warningText(Presets.OPENAI, "abc12345")!!.render(fr))
        assertEquals("Ça ne ressemble pas à une clé d’API$nb: elle devrait compter de 8 à 512 lettres, chiffres et symboles, sans espaces.",
            HelperKeyFormat.refusalText(Presets.OPENAI).render(fr))
        assertEquals("C’est un nom Tailscale$nb: il ne se résout que lorsque Tailscale est activé.", LanRule.normalize("box.tail1234.ts.net")!!.hintText!!.render(fr))
        assertEquals("C’est une adresse link-local, qui ne fonctionne qu’avec une connexion directe.", LanRule.Range.LINK_LOCAL.warningText!!.render(fr))
        assertEquals("coût inconnu", Pricing.lineText(null)!!.render(fr))
        assertEquals("11 octobre 2026", Pricing.dateText(PriceTable.AS_OF).render(fr))
        assertEquals("1er janvier 2027", Pricing.dateText(LocalDate.of(2027, 1, 1)).render(fr))
        assertEquals("1 Jan 2027", Pricing.dateText(LocalDate.of(2027, 1, 1)).en())
        assertEquals("0,075$nb\$${nb}US", HelperFormat.perMillion(0.075, Lang.FR))
        assertEquals("12,50$nb\$${nb}US", HelperFormat.perMillion(12.5, Lang.FR))
        assertEquals("0,42$nb\$${nb}US", HelperFormat.dollars(420_000, Lang.FR))
        assertEquals("5$nb\$${nb}US", HelperFormat.budget(5_000_000, Lang.FR))
        assertEquals("0,5$nb¢", HelperFormat.cents("0.5", Lang.FR))
        assertEquals("1,2", HelperFormat.oneDecimal(1.2, Lang.FR))
        assertEquals("«${nb}Max$nb» est un mot ou un nom courant$nb: «${nb}demande à Max …$nb» ira à cette IA externe, jamais à quelqu’un qui s’appelle Max.",
            (HelperNames.check("Max", "new", s, wake) as NameCheck.Warning).text.render(fr))
    }
}
