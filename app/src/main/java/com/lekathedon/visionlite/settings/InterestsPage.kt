package com.lekathedon.visionlite.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lekathedon.visionlite.anime.Hubs
import com.lekathedon.visionlite.recs.Interests
import com.lekathedon.visionlite.ui.Button
import com.lekathedon.visionlite.ui.LocalLook
import com.lekathedon.visionlite.ui.Palette

/**
 * Your interests (like IMDb's): what the engine learned you watch most, and every IMDb genre to like yourself. A liked
 * one always gets its turn in the "Because you like <genre>" rows and counts fully in Vision Picks.
 */
@Composable
fun InterestsPage() {
    val tick by Interests.changed.collectAsState()
    val hub = remember { Hubs.now() }
    val learned = remember(tick) { Interests.learned(hub) }
    val liked = remember(tick) { Interests.liked() }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    fun label(id: String) = (if (id in liked) "♥ " else "") + (Interests.name(id) ?: id)
    LazyColumn(Modifier.fillMaxSize().background(LocalLook.current.page), contentPadding = PaddingValues(48.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Your interests", color = Palette.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("The subgenres you watch most, learned from your history and ratings, each gets a \"Because you like …\" row " +
                "(broad genres like Crime have their tiles on Home). Like one to give it a row for sure; liking a broad genre " +
                "brings your favourite subgenres of it. ♥ = liked.", color = Palette.TextDim, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
        }
        item {
            Text("Learned from your watching", color = Palette.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            if (learned.isEmpty()) Text("Nothing yet: learned the next time Vision Picks builds (needs Trakt history or Like / Love on titles).",
                color = Palette.TextDim, fontSize = 14.sp)
        }
        if (learned.isNotEmpty()) item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                items(learned.take(15), key = { it.id }) { l ->
                    Button(label(l.id) + "  · ${l.titles}", { Interests.toggle(l.id) }, if (l == learned.first()) Modifier.focusRequester(first) else Modifier)
                }
            }
        }
        item { Text("By region", color = Palette.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp)) }
        Interests.REGIONS.keys.forEach { r ->
            item(key = "rg:$r") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                    items(Interests.FAMILIES.keys.toList(), key = { it }) { f -> val id = Interests.regional(r, f); Button(label(id), { Interests.toggle(id) }) }
                }
            }
        }
        Interests.GROUPS.forEach { (group, range) ->
            val ids = Interests.inGroup(range)
            if (ids.isNotEmpty()) item(key = group) {
                Text(group, color = Palette.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                    items(ids, key = { it }) { id ->
                        Button(label(id), { Interests.toggle(id) }, if (learned.isEmpty() && group == Interests.GROUPS.first().first && id == ids.first()) Modifier.focusRequester(first) else Modifier)
                    }
                }
            }
        }
    }
}
