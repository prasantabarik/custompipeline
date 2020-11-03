package com.epam.edp.customStages.impl.ci.impl.tests


import com.epam.edp.stages.impl.ci.ProjectType
import com.epam.edp.stages.impl.ci.Stage
import hudson.FilePath
import groovy.json.*

@Stage(name = "integration-tests", buildTool = "maven", type = ProjectType.AUTOTESTS)
class IntegrationTests {
    Script script

    void run(context) {
        script.dir("${context.workDir}") {
            def runCommandFile = new FilePath(
                    Jenkins.getInstance().getComputer(script.env['NODE_NAME']).getChannel(),
                    "${context.workDir}/run.json"
            )
            if (!runCommandFile.exists())
                script.error "[JENKINS][ERROR] There is no run.json file in the project " +
                        "${context.git.project}. Can't define command to run autotests"

            def parsedRunCommandJson = new JsonSlurperClassic().parseText(runCommandFile.readToString())
            if (!("codereview" in parsedRunCommandJson.keySet()))
                script.error "[JENKINS][ERROR] Haven't found codereview command in file run.json. " +
                        "It's mandatory to be specified, please check"

            try {
                script.withCredentials([
                        script.usernamePassword(credentialsId: "${context.nexus.credentialsId}", passwordVariable: 'PASSWORD', usernameVariable: 'USERNAME'), 
                        script.usernamePassword(credentialsId: "${context.job.getParameterValue("KEY_VAULT_SECRET")}", passwordVariable: 'KV_PASSWORD', usernameVariable: 'KV_USERNAME')
                    ]) {
                    script.sh "${parsedRunCommandJson.codereview} ${context.buildTool.properties} -Dartifactory.username=${script.USERNAME} -Dartifactory.password=${script.PASSWORD} " +
                            "-DazureKeyVaultClientId=${script.KV_USERNAME} -DazureKeyVaultClientKey=${script.KV_PASSWORD} " +
                            "-B --settings ${context.buildTool.settings}"
                }
            }

            catch (Exception ex) {
                script.error "[JENKINS][ERROR] Tests have been failed with error - ${ex}"
            }
            finally {
                switch (context.codebase.config.testReportFramework.toLowerCase()) {
                    case "allure":
                        script.allure([
                                includeProperties: false,
                                reportBuildPolicy: 'ALWAYS',
                                results          : [[path: 'target/allure-results']]
                        ])
                        break
                    default:
                        script.println("[JENKINS][WARNING] Can't publish test results. Testing framework is unknown.")
                        break
                }
            }
        }
    }
}
