import sys

with open('mvp/composeApp/src/commonMain/kotlin/com/noslop/mvp/App.kt', 'r') as f:
    text = f.read()

new_text = text.replace('import androidx.compose.material3.Tab', 'import androidx.compose.material3.NavigationBar\nimport androidx.compose.material3.NavigationBarItem\nimport androidx.compose.material3.Icon')
new_text = new_text.replace('import androidx.compose.material3.TabRow', 'import com.noslop.mvp.ui.theme.NoSlopTheme\nimport androidx.compose.material.icons.Icons\nimport androidx.compose.material.icons.filled.Home\nimport androidx.compose.material.icons.filled.Settings\nimport androidx.compose.material.icons.filled.Person\nimport androidx.compose.material.icons.filled.Chat\nimport androidx.compose.material.icons.filled.Share')

old_scaffold = '''                    TabRow(selectedTabIndex = tab) {
                        Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Identity") })
                        Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Feed") })
                        Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Mesh") })
                        Tab(selected = tab == 3, onClick = { tab = 3 }, text = { Text("Chat") })
                        Tab(selected = tab == 4, onClick = { tab = 4 }, text = { Text("Settings") })
                    }
                    when (tab) {
                        0 -> IdentityScreen()
                        1 -> FeedScreen()
                        2 -> MeshScreen(mesh)
                        3 -> ChatScreen()
                        else -> SettingsTabWrapper()
                    }'''

new_scaffold = '''                    Box(modifier = Modifier.weight(1f)) {
                        when (tab) {
                            0 -> FeedScreen()
                            1 -> MeshScreen(mesh)
                            2 -> ChatScreen()
                            3 -> IdentityScreen()
                            else -> SettingsTabWrapper()
                        }
                    }
                    NavigationBar(containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surface) {
                        NavigationBarItem(
                            selected = tab == 0, onClick = { tab = 0 },
                            icon = { Icon(Icons.Filled.Home, "Feed") }, label = { Text("Feed") }
                        )
                        NavigationBarItem(
                            selected = tab == 1, onClick = { tab = 1 },
                            icon = { Icon(Icons.Filled.Share, "Mesh") }, label = { Text("HaiNet") }
                        )
                        NavigationBarItem(
                            selected = tab == 2, onClick = { tab = 2 },
                            icon = { Icon(Icons.Filled.Chat, "Chat") }, label = { Text("DMs") }
                        )
                        NavigationBarItem(
                            selected = tab == 3, onClick = { tab = 3 },
                            icon = { Icon(Icons.Filled.Person, "Identity") }, label = { Text("Identity") }
                        )
                        NavigationBarItem(
                            selected = tab == 4, onClick = { tab = 4 },
                            icon = { Icon(Icons.Filled.Settings, "Settings") }, label = { Text("Settings") }
                        )
                    }'''

new_text = new_text.replace(old_scaffold, new_scaffold)
new_text = new_text.replace('MaterialTheme {', 'NoSlopTheme {')

with open('mvp/composeApp/src/commonMain/kotlin/com/noslop/mvp/App.kt', 'w') as f:
    f.write(new_text)

