package com.epam.edp.customStages.impl.cd.impl

import com.epam.edp.stages.impl.cd.Stage

@Stage(name = "security-check")
class SecurityCheck {
    Script script
    void run(context) {
        def codebasesList = context.job.codebasesList
        codebasesList.each() { codebase -> 
            def options = "${context.job.getParameterValue("OPTIONS_FOR_SECURITY_CHECK")} ${codebase.name}"
            script.dir("${context.workDir}") {
                script.withCredentials([script.string(credentialsId: "${context.job.stageName}.url", variable: "serverUrl")]) {
                    script.withKubeConfig([credentialsId: "${context.job.stageName}.token", serverUrl: "${script.serverUrl}"]) {
                        context.securityCheckOut = script.sh(
                            script: "kubectl -n ${context.job.deployProject} run --image=${context.job.getParameterValue("IMAGE_FOR_SECURITY_CHECK")} --rm -i --restart=Never ${context.job.getParameterValue("SECURITY_CHECK_NAME")} ${options}",
                            returnStdout: true
                        ).trim().split('\n')
                    }
                }
            }
            //Credentials for Report Portal should be created in Jenkins before running this job
            script.node("python") {
                        script.sh(
                            script: "pip install reportportal-client==5.0.3",
                            returnStdout: true)
                script.withCredentials([script.string(credentialsId: "rp.tims_reporter", variable: "token")]) {
                    script.println("[JENKINS][DEBUG] ${context.securityCheckOut}")
                    def result = context.securityCheckOut.toString().replaceAll('\\"', '')
                    script.sh(
                        script: "echo 'from time import time\nfrom reportportal_client import ReportPortalService\ndef timestamp():\n    return str(int(time() * 1000))\nendpoint = \"${context.job.getParameterValue("REPORT_PORTAL_URL")}\"\nproject = \"${context.job.getParameterValue("REPORT_PORTAL_PROJECT")}\"\nlaunch_name = \"${codebase.name.toUpperCase()} SECURITY TESTS\"\nlaunch_doc = \"Security tests for ${codebase.name} service\"\nattributes = [\"security\"]\nservice = ReportPortalService(endpoint=endpoint, project=project, token=\"${script.token}\")\nlaunch = service.start_launch(name=launch_name, start_time=timestamp(), description=launch_doc, attributes=attributes)\nitem_id = service.start_test_item(name=\"${context.job.getParameterValue("SECURITY_CHECK_NAME")}\", start_time=timestamp(), item_type=\"STEP\")\nservice.log(time=timestamp(), message=\"${result}\", level=\"INFO\")\nservice.finish_test_item(item_id=item_id, end_time=timestamp(), status=\"PASSED\")\nservice.finish_launch(end_time=timestamp())\nservice.terminate()' > rp.py",
                        returnStdout: false
                    )
                    script.sh(
                        script: "python rp.py",
                        returnStdout: true
                    ).trim()
                }
            }      
        }
    }
}