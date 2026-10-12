import org.gradle.api.credentials.HttpHeaderCredentials
import org.gradle.authentication.http.HttpHeaderAuthentication

plugins {
    id("com.vanniktech.maven.publish")
}

mavenPublishing {
    val projectGitUrl = "https://github.com/Merseyside/mersey-kmp-jobs"

    pom {
        name.set("Mersey multiplatform jobs")
        description.set(
            "Long-running background jobs with step checkpoints, written on kotlin multiplatform"
        )
        url.set(projectGitUrl)

        licenses {
            license {
                name.set("MIT")
                url.set("https://opensource.org/licenses/MIT")
            }
        }

        developers {
            developer {
                id.set("Merseyside")
                name.set("Ivan Sablin")
                email.set("ivanklessablin@gmail.com")
            }
        }

        issueManagement {
            system.set("GitHub")
            url.set("$projectGitUrl/issues")
        }

        scm {
            connection.set("scm:git:$projectGitUrl")
            developerConnection.set("scm:git:$projectGitUrl")
            url.set(projectGitUrl)
        }
    }

    publishToMavenCentral()
}

publishing {
    // GitLab package registry of this project. Declared only inside GitLab CI.
    val gitlabApiUrl: String? = System.getenv("CI_API_V4_URL")
    if (gitlabApiUrl != null) {
        repositories {
            maven {
                name = "GitLab"
                url = uri("$gitlabApiUrl/projects/${System.getenv("CI_PROJECT_ID")}/packages/maven")
                credentials(HttpHeaderCredentials::class) {
                    name = "Job-Token"
                    value = System.getenv("CI_JOB_TOKEN")
                }
                authentication {
                    create<HttpHeaderAuthentication>("header")
                }
            }
        }
    }
}
