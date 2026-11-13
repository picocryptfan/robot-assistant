// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.wiki

/**
 * P0's 50 questions (labs/wikipedia-p0/questions.tsv on main): id, language, kind, question, the expected articles
 * (| = any of; none = nothing should be offered) and the subject WikiRoute should find.
 */
internal object WikiP0 {
    data class Row(val id: Int, val lang: String, val kind: String, val question: String, val expected: List<String>, val subject: String?)

    private const val TABLE = """
1	en	fact	Who was Louis Riel?	Louis Riel	Louis Riel
2	en	fact	What is photosynthesis?	Photosynthesis	photosynthesis
3	en	fact	Where is Halifax?	Halifax, Nova Scotia	Halifax
4	en	fact	Who wrote Pride and Prejudice?	Pride and Prejudice|Jane Austen	Pride and Prejudice
5	en	fact	Tell me about the Great Wall of China	Great Wall of China	Great Wall of China
6	en	fact	When was the Battle of Hastings?	Battle of Hastings	Battle of Hastings
7	en	fact	What is a black hole?	Black hole	black hole
8	en	fact	Who was Marie Curie?	Marie Curie	Marie Curie
9	en	fact	What is the Treaty of Versailles?	Treaty of Versailles	Treaty of Versailles
10	en	fact	Where is Mount Kilimanjaro?	Mount Kilimanjaro	Mount Kilimanjaro
11	en	fact	Who painted the Mona Lisa?	Mona Lisa|Leonardo da Vinci	Mona Lisa
12	en	fact	What is the Bluenose?	Bluenose	Bluenose
13	en	fact	Tell me about the Halifax Explosion	Halifax Explosion	Halifax Explosion
14	en	fact	What is CRISPR?	CRISPR	CRISPR
15	en	fact	Who was Terry Fox?	Terry Fox	Terry Fox
16	en	fact	What is the Bay of Fundy?	Bay of Fundy	Bay of Fundy
17	en	fact	When did the Titanic sink?	Titanic|Sinking of the Titanic	Titanic sink
18	en	fact	What is a tardigrade?	Tardigrade	tardigrade
19	en	fact	Who invented the telephone?	Invention of the telephone|Telephone|Alexander Graham Bell	telephone
20	en	fact	What is the speed of light?	Speed of light	speed of light
21	en	fact	Tell me about Viola Desmond	Viola Desmond	Viola Desmond
22	en	fact	What is the Canadian Shield?	Canadian Shield	Canadian Shield
23	en	fact	Who was Cleopatra?	Cleopatra	Cleopatra
24	en	fact	What is an aurora?	Aurora	aurora
25	en	personal	What's on my calendar tomorrow?	none	-
26	en	fresh	Who won the hockey game last night?	none	-
27	en	fresh	What's the weather today?	none	-
28	en	command	Set a timer for ten minutes	none	-
29	en	personal	Where did I park my car?	none	-
30	en	command	Text my sister that I'm running late	none	-
31	fr	fact	Qui était Louis Riel?	Louis Riel	Louis Riel
32	fr	fact	C'est quoi la photosynthèse?	Photosynthèse	photosynthèse
33	fr	fact	Où se trouve Chicoutimi?	Chicoutimi	Chicoutimi
34	fr	fact	Qui était René Lévesque?	René Lévesque	René Lévesque
35	fr	fact	Parle-moi de la Révolution tranquille	Révolution tranquille	Révolution tranquille
36	fr	fact	C'est quoi la poutine?	Poutine	poutine
37	fr	fact	Qui a fondé Québec?	Samuel de Champlain|Québec (ville)	Québec
38	fr	fact	Qu'est-ce que le sirop d'érable?	Sirop d'érable	sirop d'érable
39	fr	fact	Qui était Maurice Richard?	Maurice Richard	Maurice Richard
40	fr	fact	Où est le Rocher Percé?	Rocher Percé	Rocher Percé
41	fr	fact	Quand a eu lieu la bataille des plaines d'Abraham?	Bataille des plaines d'Abraham	bataille des plaines d'Abraham
42	fr	fact	C'est quoi un trou noir?	Trou noir	trou noir
43	fr	fact	Qui est Céline Dion?	Céline Dion	Céline Dion
44	fr	fact	Parle-moi du fleuve Saint-Laurent	Fleuve Saint-Laurent|Saint-Laurent (fleuve)	fleuve Saint-Laurent
45	fr	fact	C'est quoi la Nouvelle-France?	Nouvelle-France	Nouvelle-France
46	fr	fact	Qui était Jacques Cartier?	Jacques Cartier	Jacques Cartier
47	fr	personal	C'est quand la fête de ma blonde?	none	-
48	fr	fresh	Qui a gagné la game hier soir?	none	-
49	fr	command	Mets une alarme à six heures	none	-
50	fr	fresh	Quel temps fait-il aujourd'hui?	none	-
"""

    val rows: List<Row> = TABLE.trim().lines().map { l ->
        val c = l.split('\t')
        Row(c[0].toInt(), c[1], c[2], c[3], if (c[4] == "none") emptyList() else c[4].split('|'), c[5].takeIf { it != "-" })
    }

    /** The two copies the table test reads: each expected article, a few near misses, in the language of its questions. */
    fun copies(): List<FakeCopy> {
        fun text(title: String) = "$title is the subject of this article, which tells what it is, where it is and why it matters."
        val en = rows.filter { it.lang == "en" }.flatMap { it.expected }.associateWith(::text) + mapOf(
            "Titanic" to "Titanic was a British ocean liner that would sink in 1912 after striking an iceberg.",
            "Halifax Explosion" to text("Halifax Explosion"),
            "List of premiers of Manitoba" to "Premiers of Manitoba since Louis Riel's provisional government.",
            "Métis" to "The Métis are a people of Western Canada; Louis Riel led them.",
            "Black Hole (film)" to "A film.",
        )
        val fr = rows.filter { it.lang == "fr" }.flatMap { it.expected }.associateWith(::text) + mapOf(
            "Québec (ville)" to "Québec est la capitale du Québec, fondée par Samuel de Champlain.",
        )
        return listOf(FakeCopy("en", pages = en), FakeCopy("fr", "2026-10-01", fr))
    }
}
