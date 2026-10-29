package ir.tafakoritech.adminapplication.data

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material.icons.rounded.DataObject
import androidx.compose.material.icons.rounded.DeveloperMode
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.serialization.Serializable

/** A plugin command that can be run on the shell. */
@Serializable
data class PluginCommand(
    val id: String,
    val name: String,
    val description: String,
    val command: String,
    val icon: String? = null,
    val category: String = "General",
)

/** A plugin that provides a set of commands for a server. */
@Serializable
data class Plugin(
    val id: String,
    val name: String,
    val description: String,
    val version: String = "1.0.0",
    val author: String = "Commander",
    val commands: List<PluginCommand> = emptyList(),
    val category: String = "General",
    val tags: List<String> = emptyList(),
)

/** A server's installed plugin info. */
@Serializable
data class ServerPlugin(
    val serverId: String,
    val pluginId: String,
    val installDate: Long = System.currentTimeMillis(),
)

/** Built-in plugin library. */
object PluginLibrary {
    fun getIcon(category: String): ImageVector = when (category) {
        "Docker" -> Icons.Rounded.Widgets
        "Git" -> Icons.Rounded.Code
        "Node.js" -> Icons.Rounded.DeveloperMode
        "Python" -> Icons.Rounded.Code
        "Database" -> Icons.Rounded.DataObject
        "DevOps" -> Icons.Rounded.AcUnit
        "Utilities" -> Icons.Rounded.Storage
        else -> Icons.Rounded.Extension
    }

    val all = listOf(
        Plugin(
            id = "docker",
            name = "Docker",
            description = "Container management and orchestration",
            category = "Docker",
            tags = listOf("containers", "devops", "deployment"),
            commands = listOf(
                PluginCommand("docker-ps", "List containers", "List all running containers", "docker ps -a"),
                PluginCommand("docker-images", "List images", "List all Docker images", "docker images"),
                PluginCommand("docker-logs", "View container logs", "Show logs for a container", "docker logs -f"),
                PluginCommand("docker-stats", "View resource usage", "Monitor container resource usage", "docker stats"),
                PluginCommand("docker-exec", "Execute command in container", "Run a command inside a container", "docker exec -it"),
                PluginCommand("docker-build", "Build image", "Build a Docker image", "docker build -t"),
                PluginCommand("docker-push", "Push to registry", "Push image to Docker registry", "docker push"),
                PluginCommand("docker-pull", "Pull from registry", "Pull image from Docker registry", "docker pull"),
            )
        ),
        Plugin(
            id = "docker-compose",
            name = "Docker Compose",
            description = "Multi-container application orchestration",
            category = "Docker",
            tags = listOf("compose", "containers", "orchestration"),
            commands = listOf(
                PluginCommand("docker-compose-up", "Start services", "Start all services defined in docker-compose.yml", "docker-compose up -d"),
                PluginCommand("docker-compose-down", "Stop services", "Stop all running services", "docker-compose down"),
                PluginCommand("docker-compose-ps", "List services", "List status of all services", "docker-compose ps"),
                PluginCommand("docker-compose-logs", "View logs", "View logs from services", "docker-compose logs -f"),
                PluginCommand("docker-compose-build", "Build services", "Build service images", "docker-compose build"),
                PluginCommand("docker-compose-exec", "Execute in service", "Run command in service container", "docker-compose exec"),
            )
        ),
        Plugin(
            id = "git",
            name = "Git",
            description = "Version control and repository management",
            category = "Git",
            tags = listOf("version-control", "scm"),
            commands = listOf(
                PluginCommand("git-status", "Repository status", "Show working tree status", "git status"),
                PluginCommand("git-log", "View commit history", "Show commit logs", "git log --oneline -10"),
                PluginCommand("git-diff", "View changes", "Show changes between commits", "git diff"),
                PluginCommand("git-branch", "List branches", "Show all branches", "git branch -a"),
                PluginCommand("git-add", "Stage changes", "Add files to staging area", "git add ."),
                PluginCommand("git-commit", "Commit changes", "Create a new commit", "git commit -m"),
                PluginCommand("git-push", "Push to remote", "Push commits to remote repository", "git push"),
                PluginCommand("git-pull", "Pull from remote", "Fetch and merge from remote", "git pull"),
            )
        ),
        Plugin(
            id = "node",
            name = "Node.js",
            description = "JavaScript runtime and package management",
            category = "Node.js",
            tags = listOf("nodejs", "npm", "javascript"),
            commands = listOf(
                PluginCommand("node-version", "Node version", "Show installed Node.js version", "node --version"),
                PluginCommand("npm-version", "npm version", "Show installed npm version", "npm --version"),
                PluginCommand("npm-install", "Install dependencies", "Install project dependencies", "npm install"),
                PluginCommand("npm-run-dev", "Start dev server", "Start development server", "npm run dev"),
                PluginCommand("npm-build", "Build project", "Build for production", "npm run build"),
                PluginCommand("npm-test", "Run tests", "Execute test suite", "npm test"),
                PluginCommand("npm-list", "List dependencies", "List installed packages", "npm list"),
            )
        ),
        Plugin(
            id = "python",
            name = "Python",
            description = "Python runtime and package management",
            category = "Python",
            tags = listOf("python", "pip", "venv"),
            commands = listOf(
                PluginCommand("python-version", "Python version", "Show installed Python version", "python --version"),
                PluginCommand("pip-list", "List packages", "List installed packages", "pip list"),
                PluginCommand("pip-install", "Install package", "Install a Python package", "pip install"),
                PluginCommand("venv-create", "Create virtual env", "Create a virtual environment", "python -m venv venv"),
                PluginCommand("venv-activate", "Activate venv", "Activate virtual environment", "source venv/bin/activate"),
                PluginCommand("pip-freeze", "Export dependencies", "Export requirements to file", "pip freeze > requirements.txt"),
            )
        ),
        Plugin(
            id = "kubernetes",
            name = "Kubernetes",
            description = "Container orchestration and cluster management",
            category = "DevOps",
            tags = listOf("k8s", "kubernetes", "orchestration"),
            commands = listOf(
                PluginCommand("kubectl-version", "Kubectl version", "Show kubectl version", "kubectl version"),
                PluginCommand("kubectl-get-nodes", "List nodes", "Show all cluster nodes", "kubectl get nodes"),
                PluginCommand("kubectl-get-pods", "List pods", "Show all running pods", "kubectl get pods -A"),
                PluginCommand("kubectl-get-services", "List services", "Show all services", "kubectl get svc -A"),
                PluginCommand("kubectl-logs", "View pod logs", "Show logs from a pod", "kubectl logs -f"),
                PluginCommand("kubectl-apply", "Apply configuration", "Apply Kubernetes manifest", "kubectl apply -f"),
                PluginCommand("kubectl-delete", "Delete resource", "Delete a Kubernetes resource", "kubectl delete"),
            )
        ),
        Plugin(
            id = "aws",
            name = "AWS CLI",
            description = "Amazon Web Services command-line interface",
            category = "DevOps",
            tags = listOf("aws", "cloud", "amazon"),
            commands = listOf(
                PluginCommand("aws-version", "AWS CLI version", "Show AWS CLI version", "aws --version"),
                PluginCommand("aws-iam-list-users", "List IAM users", "List all IAM users", "aws iam list-users"),
                PluginCommand("aws-s3-ls", "List S3 buckets", "List S3 buckets", "aws s3 ls"),
                PluginCommand("aws-ec2-describe", "List EC2 instances", "Show EC2 instances", "aws ec2 describe-instances"),
                PluginCommand("aws-rds-describe", "List RDS databases", "Show RDS instances", "aws rds describe-db-instances"),
            )
        ),
        Plugin(
            id = "postgres",
            name = "PostgreSQL",
            description = "PostgreSQL database management",
            category = "Database",
            tags = listOf("postgres", "database", "sql"),
            commands = listOf(
                PluginCommand("psql-version", "PostgreSQL version", "Show PostgreSQL version", "psql --version"),
                PluginCommand("psql-list-db", "List databases", "List all databases", "psql -l"),
                PluginCommand("psql-connect", "Connect to database", "Connect to a database", "psql -U"),
                PluginCommand("psql-dump", "Backup database", "Dump database to file", "pg_dump"),
                PluginCommand("psql-restore", "Restore database", "Restore from backup", "pg_restore"),
            )
        ),
        Plugin(
            id = "system",
            name = "System Utilities",
            description = "Common system commands and monitoring",
            category = "Utilities",
            tags = listOf("system", "monitoring", "utilities"),
            commands = listOf(
                PluginCommand("disk-usage", "Disk usage", "Show disk usage summary", "df -h"),
                PluginCommand("memory-usage", "Memory usage", "Show memory usage", "free -h"),
                PluginCommand("cpu-info", "CPU information", "Show CPU information", "lscpu"),
                PluginCommand("process-list", "Running processes", "List top processes", "top -b -n 1"),
                PluginCommand("network-status", "Network status", "Show network configuration", "ip addr"),
            )
        ),
    )

    fun getPlugin(id: String): Plugin? = all.find { it.id == id }

    fun searchPlugins(query: String): List<Plugin> {
        val q = query.lowercase()
        return all.filter { plugin ->
            plugin.name.lowercase().contains(q) ||
            plugin.description.lowercase().contains(q) ||
            plugin.tags.any { it.lowercase().contains(q) }
        }
    }

    fun getByCategory(category: String): List<Plugin> =
        all.filter { it.category == category }

    fun getCategories(): List<String> =
        all.map { it.category }.distinct().sorted()
}
