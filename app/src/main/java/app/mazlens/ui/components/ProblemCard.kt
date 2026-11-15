package app.mazlens.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.mazlens.app.ProblemExplanation

@Composable
fun ProblemCard(problem: ProblemExplanation) {
    var details by rememberSaveable(problem) { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth().testTag("problem_card"),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle(problem.title)
            Text(problem.reason, style = MaterialTheme.typography.bodyMedium)
            Text("Что сделать", style = MaterialTheme.typography.labelLarge)
            Text(problem.action, style = MaterialTheme.typography.bodyMedium)
            if (problem.details.isNotBlank()) {
                TextButton(
                    onClick = { details = !details },
                    modifier = Modifier.testTag("problem_details"),
                ) {
                    Text(if (details) "Скрыть подробности" else "Технические подробности")
                }
                if (details) Text(problem.details, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
