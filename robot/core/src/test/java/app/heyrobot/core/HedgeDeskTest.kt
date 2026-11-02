// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.1 (words-hedge, bug 2): the desk set for [Hedge]. 40 replies that hedge and 40 good answers that name a website, a
 * ministry or an agency, in English and in Québec French. The first hedge is the reply Robot's model really gave to
 * "Give me specifics about tire chain regulations on PEI in Quebec" (29 Oct 2026), which brought no offer; the others are
 * written the way Robot's model puts things. Every hedge must be found; no more than 2 of the answers may be.
 */
class HedgeDeskTest {
    private val tireChains = "I don't have specific, up-to-the-minute legal documents for tire chain regulations in Quebec for PEI. You " +
        "should check the official website for the Quebec Ministry of Transport or the provincial government's road conditions page for " +
        "the most accurate and current information."

    private val hedges = listOf(
        tireChains,
        "I don't have specific information about the current speed limits on that highway. You should check the Ministry of Transportation's official website for the most accurate details.",
        "I don't have the latest figures for Canada's unemployment rate. For the most accurate and current information, please visit the Statistics Canada website.",
        "I don't have access to the specific bylaws for your municipality. I recommend contacting your town office or checking their official website.",
        "Unfortunately, I don't have reliable, detailed records of ferry schedules between PEI and Nova Scotia. Please check the operator's official website for the most up-to-date schedule.",
        "I don't have up-to-date fishing regulations for New Brunswick. Regulations change every season, so check the official provincial website before you go.",
        "I'm sorry, but I don't have exact, current statistics on housing prices in Halifax.",
        "It's best to check the official Service Canada website for the most accurate and up-to-date information about passport processing times, as they change often.",
        "For the most accurate and current information about border wait times, please visit the official Canada Border Services Agency website.",
        "I don't currently have detailed information on that event. You may want to consult the organizer's official page for the latest details.",
        "I do not have the specific provincial regulations in front of me. Please consult the official government website for the most accurate information.",
        "I'm not able to give you the current price of gas in Moncton. Prices change daily, so check a local gas price website.",
        "My knowledge has a cutoff date, so I can't tell you who won last night's game. Check a sports website for the latest scores.",
        "I don't have real-time data on flight delays. Please check the airline's official website or app.",
        "I can't provide legal advice about tenant rights in Quebec. You should consult the Tribunal administratif du logement's official website for the most accurate information.",
        "I don't have enough details about your insurance policy to answer that. Contact your insurer directly.",
        "I'm not sure about the exact opening hours of the Confederation Centre today. Check their official website for the most current information.",
        "I don't have trustworthy, verified sources for that claim, so I can't confirm it.",
        "I don't have any specific documents about the 2026 changes to the tax rules. Please visit the Canada Revenue Agency's official website for the most up-to-date information.",
        "Unfortunately, I do not have current, province-specific data on fire bans. Visit the official provincial website for the latest information.",
        "I'm unable to look up the current exchange rate. Check your bank's website or a currency converter.",
        "Sorry, I don't have specific details on road closures right now. For up-to-the-minute conditions, check the province's 511 service.",
        "As an AI, I don't have the ability to check current weather alerts. Visit Environment Canada's official website for the latest warnings.",
        "You should check the official Parks Canada website for the most accurate and current information on campsite openings, as I can't confirm the dates.",
        "I don't have reliable, up-to-the-minute information on the hospital's emergency room wait times.",
        "That information changes often and I don't have the latest version of the regulations. Please consult the official Transport Canada website for the most accurate and up-to-date rules.",
        "I'm sorry, I don't have information about that specific restaurant.",
        "I don't have up-to-date bus schedules for Charlottetown. Check the official transit website for the most current timetable.",
        // Québec French, "tu".
        "Je n'ai pas d'informations précises sur la réglementation des chaînes à neige au Québec. Consulte le site officiel du ministère des Transports pour les informations les plus à jour.",
        "Je n'ai pas les données les plus récentes sur le taux de chômage. Consulte le site de Statistique Canada.",
        "Désolé, je n'ai pas accès aux horaires du traversier en ce moment.",
        "Je ne dispose pas d'informations à jour sur les fermetures de routes. Consulte Québec 511 pour les informations les plus récentes.",
        "Pour les informations les plus à jour sur les délais de passeport, consulte le site officiel de Service Canada.",
        "Je n'ai malheureusement pas de documents précis sur ce règlement municipal. Vérifie le site officiel de ta municipalité.",
        "Je ne peux pas te donner le prix de l'essence aujourd'hui, il change chaque jour.",
        "Je ne suis pas certain des heures d'ouverture de ce musée. Consulte le site officiel pour les renseignements les plus exacts.",
        "Mes connaissances s'arrêtent en 2024, alors je ne connais pas les résultats d'hier soir.",
        "Je n'ai pas de chiffres fiables sur le prix moyen des maisons à Moncton.",
        "Il vaut mieux consulter le site officiel de l'Agence du revenu du Canada pour les informations les plus exactes et à jour sur ce crédit d'impôt.",
        "Je n'ai pas de renseignements à jour sur les feux de forêt dans ta région. Visite le site officiel de la SOPFEU.",
    )

    private val answers = listOf(
        "In Quebec, winter tires are mandatory from December 1 to March 15 for all passenger vehicles registered in the province. Studded tires are allowed from October 15 to May 1. Tire chains are permitted on some mountain roads but not on most highways. For the most up-to-date information, check the official Transports Québec website.",
        "On Prince Edward Island, tire chains are not commonly used, and the province does not require winter tires, though they are strongly recommended. Studded tires are allowed from October 1 to May 31. You can find the full rules on the provincial government's official website.",
        "The Canada Revenue Agency's deadline for most individual tax returns is April 30. If you or your spouse are self-employed, you have until June 15 to file, but any balance owing is still due April 30. For the most accurate information, visit the official CRA website.",
        "To renew a Canadian passport, you can use the simplified renewal form if your passport was issued in the last 15 years and you were 16 or older. You'll need two photos, your old passport and the fee. Check the official Service Canada website for current fees.",
        "Ferries between Wood Islands, PEI and Caribou, Nova Scotia run from early May to late December. The crossing takes about 75 minutes. Schedules vary by season, so check the operator's official website before you travel.",
        "The Confederation Bridge is 12.9 kilometres long and connects Borden-Carleton, PEI to Cape Jourimain, New Brunswick. The toll is paid only when leaving the island. Current toll rates are listed on the bridge's official website.",
        "The Ontario Ministry of Transportation sets the speed limit on most 400-series highways at 100 km/h, with some sections raised to 110 km/h. Always follow posted signs, and check the ministry's website for the most current list of sections.",
        "Health Canada recommends adults get at least 150 minutes of moderate to vigorous activity each week, plus muscle-strengthening activities twice a week. The full guidelines are on the Health Canada website.",
        "To get a fishing licence in New Brunswick, buy one online through the provincial Department of Natural Resources website or from a licensed vendor. A resident angling licence covers most freshwater species. Check the official website for the most up-to-date seasons and limits.",
        "In Nova Scotia, you can apply for a driver's licence at 16. You first take a knowledge test to get a learner's licence, then hold it for at least nine months. Access Nova Scotia's official website lists the documents you need.",
        "The Canadian Food Inspection Agency says you shouldn't bring fresh meat, many fruits or plants back into Canada without declaring them. Declare everything at the border. For the most accurate and current rules, check the CFIA website.",
        "Statistics Canada's 2021 census counted 154,331 people on Prince Edward Island. The province has grown since then, mainly through immigration. For the latest population estimates, visit the Statistics Canada website.",
        "Quebec's minimum wage is \$15.75 an hour as of May 2024. Tipped employees have a lower rate. The CNESST website has the current rates and any upcoming changes.",
        "Passenger vehicles in Quebec must carry winter tires marked with the three-peak mountain snowflake symbol from December 1 to March 15. The fine for driving without them ranges from \$200 to \$300. Transports Québec's official website has the full regulation.",
        "Parks Canada campgrounds usually open for reservations in January. Popular parks like Cavendish fill up within hours, so plan ahead. You can book on the official Parks Canada reservation website.",
        "The speed limit on most PEI highways is 90 km/h, and 50 km/h in towns unless posted. School zones drop to 40 km/h when children are present. The province's official website has the Highway Traffic Act if you want the details.",
        "To register a boat in Canada, pleasure craft with motors of 10 horsepower or more need a licence, which is free from Transport Canada. You can apply online on the Transport Canada website. Remember to carry proof of competency too.",
        "Environment Canada issues a winter storm warning when 15 cm or more of snow is expected within 12 hours, or when blowing snow will reduce visibility. Warnings appear on the official weather website and in the WeatherCAN app.",
        "The Ministry of Health in Ontario covers most doctor visits under OHIP, but not prescription drugs for most adults aged 25 to 64. Check the ministry's website for the list of covered services and how to apply.",
        "Tenants in Quebec must receive a lease renewal notice between three and six months before a 12-month lease ends. You then have one month to refuse a rent increase. The Tribunal administratif du logement's website explains each step.",
        "A Canadian citizen needs a valid passport to fly to the United States. By land or sea, a NEXUS card or enhanced driver's licence also works. Check the official U.S. Customs and Border Protection website for the most current entry rules before you go.",
        "Recycling in Charlottetown uses a three-cart system: blue for recyclables, green for compost and black for waste. Pickup alternates weekly. The Island Waste Management Corporation's website has the collection calendar for your street.",
        "To vote in a federal election, you must be a Canadian citizen, at least 18 on election day, and prove your identity and address. Elections Canada's official website lists the accepted pieces of ID.",
        "The Ministry of Transportation recommends carrying a winter survival kit: a shovel, sand or kitty litter, a flashlight, blankets, and food and water. Keep your gas tank at least half full in winter. You'll find a full checklist on the ministry's website.",
        "Lobster season on PEI's north shore usually runs from late April to late June, and on the south shore from August to October. Exact dates are set each year by Fisheries and Oceans Canada; check their official website for this year's dates.",
        "You can bring up to \$800 worth of goods back to Canada duty-free after more than 48 hours away. After 24 hours, the limit is \$200. For the most up-to-date exemptions, see the Canada Border Services Agency website.",
        "Driving with a phone in your hand is illegal in every Canadian province. In Quebec the fine is \$300 to \$600 plus five demerit points. The SAAQ website has the full rules for hands-free devices.",
        "The Quebec Ministry of Transport allows tire chains only on vehicles used in specific mountain areas and on private roads. Ordinary passenger cars should use winter tires instead. For the most accurate and current information, check the official Transports Québec website.",
        // Québec French, "tu".
        "Au Québec, les pneus d'hiver sont obligatoires du 1er décembre au 15 mars. Les pneus à crampons sont permis du 15 octobre au 1er mai. Pour les informations les plus à jour, consulte le site officiel de Transports Québec.",
        "Le salaire minimum au Québec est de 15,75 \$ l'heure depuis mai 2024. Le site de la CNESST donne les taux à jour.",
        "Pour renouveler ton passeport, tu peux utiliser le formulaire simplifié si ton passeport a été délivré il y a moins de 15 ans. Consulte le site officiel de Service Canada pour les frais actuels.",
        "Le pont de la Confédération mesure 12,9 kilomètres. Le péage se paie seulement en quittant l'île. Les tarifs sont sur le site officiel du pont.",
        "Les locataires au Québec doivent recevoir un avis de renouvellement de bail de trois à six mois avant la fin. Le site du Tribunal administratif du logement explique chaque étape.",
        "Pour voter à une élection fédérale, il faut être citoyen canadien et avoir 18 ans le jour du vote. Le site officiel d'Élections Canada donne la liste des pièces d'identité acceptées.",
        "Le ministère des Transports recommande de garder dans l'auto une pelle, du sable, une lampe de poche et une couverture en hiver. Tu trouveras la liste complète sur le site du ministère.",
        "Tu peux rapporter jusqu'à 800 \$ de marchandises au Canada sans droits après 48 heures à l'étranger. Pour les informations les plus récentes, consulte le site de l'Agence des services frontaliers du Canada.",
        "La saison du homard sur la côte nord de l'Île-du-Prince-Édouard va habituellement de la fin avril à la fin juin. Pêches et Océans Canada fixe les dates chaque année ; vérifie son site officiel.",
        "Utiliser un cellulaire au volant est interdit au Québec. L'amende va de 300 \$ à 600 \$ avec cinq points d'inaptitude. Le site de la SAAQ donne tous les détails.",
        "Santé Canada recommande 150 minutes d'activité physique modérée à vigoureuse par semaine pour les adultes. Les lignes directrices complètes sont sur le site de Santé Canada.",
        "Pour obtenir un permis de pêche au Nouveau-Brunswick, achète-le en ligne sur le site du ministère des Ressources naturelles ou chez un vendeur autorisé. Consulte le site officiel pour les saisons et les limites les plus à jour.",
    )

    @Test fun theTireChainReplyIsAHedgeThatNeedsSearch() {
        assertTrue(Hedge.detect(tireChains))
        assertTrue(Hedge.needsSearch(tireChains))
    }

    @Test fun theDeskSet() {
        assertTrue(hedges.size >= 40 && answers.size >= 40)
        val missed = hedges.filterNot(Hedge::detect)
        val flagged = answers.filter(Hedge::detect)
        // The numbers go in the job's report: hedges found of all, answers flagged of all.
        println("desk set: hedges ${hedges.size - missed.size}/${hedges.size}, answers flagged ${flagged.size}/${answers.size}")
        assertTrue("hedges missed ${missed.size}/${hedges.size}: $missed", missed.isEmpty())
        assertTrue("answers flagged ${flagged.size}/${answers.size}: $flagged", flagged.size <= 2)
    }
}
