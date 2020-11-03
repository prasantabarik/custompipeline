import com.epam.edp.stages.impl.ci.ProjectType
import com.epam.edp.stages.impl.ci.Stage
import com.epam.edp.stages.impl.ci.impl.sonarcleanup.SonarCleanupApplicationLibrary
import org.apache.commons.lang.RandomStringUtils

@Stage(name = "sonar", buildTool = ["maven"], type = [ProjectType.APPLICATION, ProjectType.LIBRARY])
class CustomSonarStage {
    Script script
    void run(context) {
        script.dir("${context.workDir}") {
            script.withSonarQubeEnv('Sonar') {
                script.withCredentials([script.usernamePassword(credentialsId: "${context.nexus.credentialsId}",
                        passwordVariable: 'PASSWORD', usernameVariable: 'USERNAME')]) {
                    String branchName = (context.job.type == "codereview") ? context.job.getParameterValue("GIT_BRANCH") : context.job.getParameterValue("BRANCH")
                    script.sh "${context.buildTool.command} ${context.buildTool.properties} -Dartifactory.username=${script.USERNAME} -Dartifactory.password=${script.PASSWORD} " +
                            "sonar:sonar " +
                            "-Dsonar.projectKey=${context.codebase.name} " +
                            "-Dsonar.projectName=${context.codebase.name} " +
                            "-Dsonar.branch.name=${branchName} "
                }
            }
            script.timeout(time: 10, unit: 'MINUTES') {
                def qualityGateResult = script.waitForQualityGate()
                if (qualityGateResult.status != 'OK')
                    script.error "[JENKINS][ERROR] Sonar quality gate check has been failed with status " +
                            "${qualityGateResult.status}"
            }
        }
    }
}

return CustomSonarStage
