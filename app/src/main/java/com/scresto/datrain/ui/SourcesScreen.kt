package com.scresto.datrain.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp

private const val LICENCE_MOBILITES = "https://cloud.fabmob.io/s/eYWWJBdM3fQiFNm"
private const val ODBL = "https://opendatacommons.org/licenses/odbl/1-0/"

private const val APACHE = "https://www.apache.org/licenses/LICENSE-2.0"

private val OPEN_SOURCE = listOf(
    Triple("Kotlin, kotlinx.coroutines, kotlinx.serialization", "Apache 2.0", APACHE),
    Triple("AndroidX et Jetpack Compose", "Apache 2.0", APACHE),
    Triple("OkHttp et Okio", "Apache 2.0", APACHE),
    Triple("MapLibre Native", "BSD 2-Clause", "https://github.com/maplibre/maplibre-native/blob/main/LICENSE.md"),
    Triple("Police Rubik, pour le logo", "SIL Open Font License 1.1", "https://openfontlicense.org"),
)

/** Credits required by the data licences, and limits to know about. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val linkColor = MaterialTheme.colorScheme.primary

    fun text(vararg parts: Pair<String, String?>): AnnotatedString = buildAnnotatedString {
        parts.forEach { (label, url) ->
            if (url == null) {
                append(label)
            } else {
                withLink(
                    LinkAnnotation.Url(
                        url,
                        TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
                    ),
                ) { append(label) }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Informations légales") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Heading("Horaires") }
            item {
                Text(
                    text(
                        "Contient des informations de " to null,
                        "Horaires SNCF" to "https://data.sncf.com/explore/dataset/horaires-sncf/",
                        " (SNCF Voyageurs), mises à disposition aux conditions de la licence " to null,
                        "ODbL" to ODBL,
                        "." to null,
                    ),
                )
            }
            item {
                Text(
                    text(
                        "Contient des informations de " to null,
                        "Horaires prévus sur les lignes de transport en commun d'Île-de-France (GTFS Datahub)" to
                            "https://data.iledefrance-mobilites.fr/explore/dataset/offre-horaires-tc-gtfs-idfm/",
                        " (Île-de-France Mobilités), présentement mises à disposition aux conditions de la « " to null,
                        "Licence Mobilités" to LICENCE_MOBILITES,
                        " »." to null,
                    ),
                )
            }
            item { Text("Ces fichiers sont filtrés pour ne garder que les trains et les cars, convertis dans un format compact, puis combinés sur votre téléphone.") }
            item {
                Text(text("Méthode et fichiers transformés : " to null, "github.com/scresto09/datrain" to "https://github.com/scresto09/datrain"))
            }
            item { Heading("Temps réel") }
            item {
                Text(
                    text(
                        "Données SNCF (ODbL), via " to null,
                        "Train Nomade" to "https://www.data.gouv.fr/dataservices/api-train-nomade-departs-et-arrivees-des-gares-sncf-temps-reel-et-horaires-prevus",
                        ", site indépendant sans lien avec la SNCF." to null,
                    ),
                )
            }
            item {
                Text(
                    text(
                        "Retards et perturbations SNCF au format GTFS-RT, via le " to null,
                        "Point d'accès national" to "https://transport.data.gouv.fr/datasets/horaires-sncf",
                        "." to null,
                    ),
                )
            }
            item { Heading("Carte") }
            item {
                Text(
                    text(
                        "OpenFreeMap © OpenMapTiles, données " to null,
                        "OpenStreetMap" to "https://www.openstreetmap.org/copyright",
                        ". Voies : Train Nomade." to null,
                    ),
                )
            }
            item { Heading("Code source") }
            item {
                Text(
                    text(
                        "Sous licence " to null,
                        "GPL 3" to "https://www.gnu.org/licenses/gpl-3.0.html",
                        " : " to null,
                        "github.com/scresto09/datrain" to "https://github.com/scresto09/datrain",
                    ),
                )
            }
            item { Heading("Logiciels libres") }
            items(OPEN_SOURCE) { (name, licence, url) ->
                Text(text("$name : " to null, licence to url))
            }
            item { Heading("Aucune garantie") }
            item {
                Text("Datrain est un logiciel libre fourni tel quel, sans aucune garantie, et vous l'utilisez à vos risques et périls. Dans les limites permises par la loi, son auteur n'est pas responsable des conséquences de son utilisation, par exemple d'un train manqué ou d'une information erronée. Vérifiez vos horaires auprès du transporteur.")
            }
            item { Heading("À savoir") }
            item { Text("Datrain est une application indépendante, sans lien avec la SNCF ni Île-de-France Mobilités. Elle ne vend pas de billets et ne garantit pas que les correspondances proposées puissent être réservées ensemble.") }
            item { Text("La position d'un train est estimée d'après ses horaires et ses retards : ce n'est pas un GPS.") }
            item { Text("Les temps de changement sont des estimations : 10 min dans une même gare, 15 min à pied entre deux gares proches, 1 h entre deux grandes gares parisiennes. Seuls certains temps de marche en Île-de-France sont officiels.") }
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium)
}
