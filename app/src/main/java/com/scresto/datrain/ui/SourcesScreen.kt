package com.scresto.datrain.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
                title = { Text("Sources des données") },
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
            item { Text("Ces fichiers sont filtrés pour ne garder que les trains et les cars, convertis dans un format compact, puis combinés sur ton téléphone.") }
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
