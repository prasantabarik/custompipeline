package com.epam.edp.customStages.impl.cd.impl

import org.apache.commons.lang.RandomStringUtils
import com.epam.edp.stages.impl.cd.Stage
import groovy.json.JsonSlurperClassic
import hudson.FilePath
import com.epam.edp.buildtool.BuildToolFactory

@Stage(name = "perf-tests")
class PerformanceTests {
    Script script

    def generateSshLink(context, qualityGate) {
        def commonSshLinkPart = "ssh://${context.job.autouser}@${context.job.host}:${context.job.sshPort}"
        return qualityGate.autotest.gitProjectPath?.trim() ?
                "${commonSshLinkPart}${qualityGate.autotest.gitProjectPath}" :
                "${commonSshLinkPart}/${qualityGate.autotest.name}"
    }

    void run(context) {
        def qualityGate = context.job.qualityGates.find{it.stepName == context.stepName}
        def slave = context.job.getCodebaseFromAdminConsole(qualityGate.autotest.name).jenkinsSlave
        script.println("[JENKINS][DEBUG] Quality gate content - ${qualityGate}")

        script.node(slave) {
            context.buildTool = new BuildToolFactory().getBuildToolImpl(qualityGate.autotest.build_tool, script, context.nexus, context.job)
            context.buildTool.init()
            context.job.setGitServerDataToJobContext(qualityGate.autotest.gitServer)

            def codebaseDir = "${script.WORKSPACE}/${RandomStringUtils.random(10, true, true)}/${qualityGate.autotest.name}"
            script.dir("${codebaseDir}") {
                def gitCodebaseUrl = generateSshLink(context, qualityGate)

                script.checkout([$class                           : 'GitSCM', branches: [[name: "${qualityGate.codebaseBranch.branchName}"]],
                                 doGenerateSubmoduleConfigurations: false, extensions: [],
                                 submoduleCfg                     : [],
                                 userRemoteConfigs                : [[credentialsId: "${context.job.credentialsId}",
                                                                      url          : "${gitCodebaseUrl}"]]])

                if (!script.fileExists("${codebaseDir}/run.json"))
                    script.error "[JENKINS][ERROR] There is no run.json file in the project ${qualityGate.autotest.name}. " +
                            "Can't define command to run autotests"

                def runCommandFile = ""
                if (script.env['NODE_NAME'].equals("master")) {
                    def jsonFile = new File("${codebaseDir}/run.json")
                    runCommandFile = new FilePath(jsonFile).readToString()
                } else {
                    runCommandFile = new FilePath(
                            Jenkins.getInstance().getComputer(script.env['NODE_NAME']).getChannel(),
                            "${codebaseDir}/run.json").readToString()
                }

                def parsedRunCommandJson = new JsonSlurperClassic().parseText(runCommandFile)

                if (!(context.job.stageName in parsedRunCommandJson.keySet()))
                    script.error "[JENKINS][ERROR] Haven't found ${context.job.stageName} command in file run.json. " +
                            "It's mandatory to be specified, please check"

                def runCommand = parsedRunCommandJson["${context.job.stageName}"]
                try {
                    script.withCredentials([
                            script.usernamePassword(credentialsId: "${context.nexus.credentialsId}", passwordVariable: 'PASSWORD', usernameVariable: 'USERNAME'), 
                            script.usernamePassword(credentialsId: "${context.job.getParameterValue("TIMS_SECRETS")}", passwordVariable: 'TIMS_PASSWORD', usernameVariable: 'TIMS_USERNAME'),
                            script.string(credentialsId: "${context.job.getParameterValue("ESL_SECRETS_RI_DIRECT")}", variable: 'ESL_DIRECT_RI_PASSWORD'),
                            script.string(credentialsId: "${context.job.getParameterValue("ESL_SECRETS_SA_DIRECT")}", variable: 'ESL_DIRECT_SA_PASSWORD'),
                            script.usernamePassword(credentialsId: "${context.job.getParameterValue("ESL_SECRETS_RI")}", passwordVariable: 'ESL_RI_PASSWORD', usernameVariable: 'ESL_RI_USERNAME'),
                            script.usernamePassword(credentialsId: "${context.job.getParameterValue("ESL_SECRETS_SA")}", passwordVariable: 'ESL_SA_PASSWORD', usernameVariable: 'ESL_SA_USERNAME'),
                        ]) {
                        script.sh "${runCommand} ${context.buildTool.properties} -Dartifactory.username=${script.USERNAME} -Dartifactory.password=${script.PASSWORD} " +
                            "-DclientId=${script.TIMS_USERNAME} -DclientSecret=${script.TIMS_PASSWORD} " +
                            "-Dahold.esl.perf-test.direct.retail-item.api-key=${script.ESL_DIRECT_RI_PASSWORD} " +
                            "-Dahold.esl.perf-test.direct.store-assortment.api-key=${script.ESL_DIRECT_SA_PASSWORD} " +
                            "-Dahold.esl.perf-test.mule.retail-item.client-id=${script.ESL_RI_USERNAME} -Dahold.esl.perf-test.mule.retail-item.client-secret=${script.ESL_RI_PASSWORD} " +
                            "-Dahold.esl.perf-test.mule.store-assortment.client-id=${script.ESL_SA_USERNAME} -Dahold.esl.perf-test.mule.store-assortment.client-secret=${script.ESL_SA_PASSWORD} " +
                            "-B --settings ${context.buildTool.settings}"
                    }
                }
                catch (Exception ex) {
                    script.error "[JENKINS][ERROR] Tests from ${qualityGate.autotest.name} have been failed. Reason - ${ex}"
                }
                finally {
                    switch ("${qualityGate.autotest.testReportFramework}") {
                        case "allure":
                            script.allure([
                                    includeProperties: false,
                                    jdk              : '',
                                    properties       : [],
                                    reportBuildPolicy: 'ALWAYS',
                                    results          : [[path: 'target/allure-results']]
                            ])
                            break
                        case "gatling":
                            script.gatlingArchive()
                            break
                        default:
                            script.println("[JENKINS][WARNING] Can't publish test results. Testing framework is undefined.")
                            break
                    }
                }
            }
        }
    }
}

