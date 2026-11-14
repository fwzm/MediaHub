package com.mediahub.feature.detail.version

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mediahub.model.MediaVersion

/**
 * 详情页版本选择面板：仅在 >= 2 个版本时出现。
 * 与跨服务器来源选择器（ADR-038）语义分离：这里选的是同一媒体在**同一服务器**上的不同版本。
 */
@Composable
fun VersionSelector(
    versions: List<MediaVersion>,
    selectedVersionId: String?,
    onSelectVersion: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!shouldShowVersionPanel(versions)) return
    val rows = versionRows(versions, selectedVersionId)
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            "版本",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(rows, key = { it.id }) { row ->
                FilterChip(
                    selected = row.isSelected,
                    onClick = { onSelectVersion(row.id) },
                    label = {
                        Column {
                            Text(row.label)
                            row.detail?.let {
                                Text(it, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    },
                )
            }
        }
    }
}
