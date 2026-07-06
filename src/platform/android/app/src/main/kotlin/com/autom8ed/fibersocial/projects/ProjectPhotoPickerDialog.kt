package com.autom8ed.fibersocial.projects

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

/**
 * Dialog for picking a photo from one of the user's Ravelry projects — the free
 * alternative to uploading a device photo (which needs Ravelry Extras to post).
 * Two steps inside one dialog: project list, then that project's photo grid.
 * Renders nothing while [state] is [ProjectPickerState.Hidden].
 */
@Composable
fun ProjectPhotoPickerDialog(
    state: ProjectPickerState,
    onProjectSelected: (ProjectSummary) -> Unit,
    onPhotoPicked: (ProjectSummary, ProjectPhoto) -> Unit,
    onBackToProjects: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (state is ProjectPickerState.Hidden) return

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        title = {
            when (state) {
                is ProjectPickerState.LoadingPhotos -> PhotoGridTitle(state.project.name, onBackToProjects)
                is ProjectPickerState.PhotoGrid -> PhotoGridTitle(state.project.name, onBackToProjects)
                else -> Text("Your project photos")
            }
        },
        text = {
            when (state) {
                is ProjectPickerState.Hidden -> Unit
                is ProjectPickerState.LoadingProjects, is ProjectPickerState.LoadingPhotos -> Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                is ProjectPickerState.Error -> Text(
                    text = state.message.ifBlank { "Couldn't load your projects. Try again." },
                    color = MaterialTheme.colorScheme.error,
                )

                is ProjectPickerState.ProjectList ->
                    if (state.projects.isEmpty()) {
                        Text("None of your projects have photos yet.")
                    } else {
                        LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                            items(state.projects, key = { it.id }) { project ->
                                ProjectRow(project, onClick = { onProjectSelected(project) })
                            }
                        }
                    }

                is ProjectPickerState.PhotoGrid -> LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.heightIn(max = 420.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(state.photos, key = { it.id }) { photo ->
                        AsyncImage(
                            model = photo.gridUrl,
                            contentDescription = "Photo from ${state.project.name}",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(4.dp))
                                .clickable { onPhotoPicked(state.project, photo) },
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun PhotoGridTitle(projectName: String, onBackToProjects: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBackToProjects) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to projects")
        }
        Text(projectName, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun ProjectRow(project: ProjectSummary, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
    ) {
        AsyncImage(
            model = project.firstPhoto?.squareUrl ?: project.firstPhoto?.gridUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)),
        )
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(project.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = if (project.photosCount == 1) "1 photo" else "${project.photosCount} photos",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
