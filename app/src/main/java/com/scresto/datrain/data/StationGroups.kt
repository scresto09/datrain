package com.scresto.datrain.data

/** Cities whose main stations have different trains: one entry searches them all. */
object StationGroups {
    class Group(val name: String, val uics: IntArray)

    val ALL = listOf(
        Group("Paris (toutes gares)", TimetableMerger.PARIS_TERMINALS),
        // Flandres, Europe
        Group("Lille (toutes gares)", intArrayOf(87286005, 87223263)),
        // Part Dieu, Perrache
        Group("Lyon (toutes gares)", intArrayOf(87723197, 87722025)),
        // Saint-Roch, Sud de France
        Group("Montpellier (toutes gares)", intArrayOf(87773002, 87688887)),
        // Centre, TGV
        Group("Avignon (toutes gares)", intArrayOf(87765008, 87318964)),
        // Tours, Saint-Pierre-des-Corps
        Group("Tours (toutes gares)", intArrayOf(87571000, 87571240)),
        // Reims, Champagne-Ardenne TGV
        Group("Reims (toutes gares)", intArrayOf(87171009, 87171926)),
        // Ville, TGV Rhône-Alpes Sud
        Group("Valence (toutes gares)", intArrayOf(87761007, 87763029)),
    )

    fun named(name: String): Group? = ALL.firstOrNull { it.name == name }
}
