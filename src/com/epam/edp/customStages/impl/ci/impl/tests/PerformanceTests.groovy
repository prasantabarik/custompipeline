package com.epam.edp.customStages.impl.ci.impl.tests


import com.epam.edp.stages.impl.ci.ProjectType
import com.epam.edp.stages.impl.ci.Stage
import hudson.FilePath
import groovy.json.*

@Stage(name = "perf-tests", buildTool = "maven", type = ProjectType.AUTOTESTS)
class PerformanceTests {
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
                        script.usernamePassword(credentialsId: "${context.job.getParameterValue("TIMS_SECRETS")}", passwordVariable: 'TIMS_PASSWORD', usernameVariable: 'TIMS_USERNAME'),
                        script.string(credentialsId: "${context.job.getParameterValue("ESL_SECRETS_RI_DIRECT")}", variable: 'ESL_DIRECT_RI_PASSWORD'),
                        script.string(credentialsId: "${context.job.getParameterValue("ESL_SECRETS_SA_DIRECT")}", variable: 'ESL_DIRECT_SA_PASSWORD'),
                        script.usernamePassword(credentialsId: "${context.job.getParameterValue("ESL_SECRETS_RI")}", passwordVariable: 'ESL_RI_PASSWORD', usernameVariable: 'ESL_RI_USERNAME'),
                        script.usernamePassword(credentialsId: "${context.job.getParameterValue("ESL_SECRETS_SA")}", passwordVariable: 'ESL_SA_PASSWORD', usernameVariable: 'ESL_SA_USERNAME'),
                    ]) {
                    script.sh "${parsedRunCommandJson.codereview} ${context.buildTool.properties} -Dartifactory.username=${script.USERNAME} -Dartifactory.password=${script.PASSWORD} " +
                        "-DclientId=${script.TIMS_USERNAME} -DclientSecret=${script.TIMS_PASSWORD} " +
                        "-Dahold.esl.perf-test.direct.retail-item.api-key=${script.ESL_DIRECT_RI_PASSWORD} " +
                        "-Dahold.esl.perf-test.direct.store-assortment.api-key=${script.ESL_DIRECT_SA_PASSWORD} " +
                        "-Dahold.esl.perf-test.mule.retail-item.client-id=${script.ESL_RI_USERNAME} -Dahold.esl.perf-test.mule.retail-item.client-secret=${script.ESL_RI_PASSWORD} " +
                        "-Dahold.esl.perf-test.mule.store-assortment.client-id=${script.ESL_SA_USERNAME} -Dahold.esl.perf-test.mule.store-assortment.client-secret=${script.ESL_SA_PASSWORD} " +
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
                    case "gatling":
                        script.gatlingArchive()
                        break
                    default:
                        script.println("[JENKINS][WARNING] Can't publish test results. Testing framework is unknown.")
                        break
                }
            }
        }
    }
}
