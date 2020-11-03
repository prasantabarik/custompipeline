/* Copyright 2019 EPAM Systems.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at
http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.

See the License for the specific language governing permissions and
limitations under the License.*/

package com.epam.edp.removepipes.database

class Database {

    Script script
    String deployProject

    String podName

    Database(String deployProject, Script script) {
        this.deployProject = deployProject
        this.script = script
    }

    /* COMMON METHODS */

    void init() {
        try {
            script.openshift.withCluster() {
                script.openshift.withProject(deployProject) {
                    def pods = script.openshift.raw("get", "pods", "-o custom-columns=NAME:.metadata.name", "--no-headers")
                    pods.out.split('\n').each { pod ->
                        if (pod.contains("edp-database")) {
                            podName = pod
                            return // breaks search if any db pod found
                        }
                    }
                }
            }
        } catch (Exception ex) {
            script.println "Exception while searching database pod : ${ex}"
        } finally {
            if (podName == null)
                script.error "Database pod could not be found"
        }
    }

    def invokeCommand(String projectName, String command) {
        def result
        try {
            script.openshift.withCluster() {
                script.openshift.withProject(deployProject) {
                    result = script.openshift.exec(podName, "-it", "--", "psql -d edp-database -U admin  -c \"" +
                            "set search_path = \'$projectName\'; $command\"").out.replaceAll(" ", "").split("\n")
                }
            }
        } catch (Exception ex) {
            script.error "Failed in execution command \"${command}\" for the \"${projectName}\" " +
                    "project in the pod \"${podName}\". Exception message:\n${ex}"
        }
        return result
    }

    ArrayList getEntries(String projectName, String command) {
        ArrayList entries = invokeCommand(projectName, command)
        removeExtraRows(entries)
        return entries
    }

    void removeExtraRows(ArrayList requestResults) {
        requestResults.removeAt(1)                          // removes heading row
        requestResults.removeAt(0)                          // removes "----+------+--------" row
        requestResults.removeAt(requestResults.size() - 1)  // removes rows count row
    }

    /* CD STAGE RELATED REQUESTS */

    ArrayList getCdStages(String projectName, String pipelineName) {
        String pipelineId = getPipelineId(projectName, pipelineName)
        return getEntries(projectName, "select name from cd_stage where cd_pipeline_id=\'$pipelineId\';")
    }

    void removeCdStage(String projectName, String pipelineName, String stageName, boolean isV2) {
        String pipelineId = getPipelineId(projectName, pipelineName)
        String stageId = invokeCommand(projectName, "select id from cd_stage where name=\'$stageName\' and cd_pipeline_id=\'$pipelineId\';")[2]

        ArrayList databaseListCommand = [
                "delete from cd_stage_action_log where cd_stage_id = \'$stageId\';",
                "delete from cd_stage_codebase_branch where cd_stage_id =\'$stageId\';",
                "delete from stage_codebase_docker_stream where cd_stage_id =\'$stageId\';",
                "delete from cd_stage where id =\'$stageId\';",
        ]

        if (isV2) {
            ArrayList apps = getCdPipelineApplications(projectName, pipelineName, true)
            apps.each { app ->
                databaseListCommand.add("delete from codebase_docker_stream where oc_image_stream_name =\'${pipelineName}-${stageName}-${app}-verified\';")
            }
        }

        script.println "Removing entries related to CD stage \"${stageName}\""

        databaseListCommand.each { command ->
            invokeCommand(projectName, command)
        }
    }

    /* CD PIPELINE RELATED REQUESTS */

    String getPipelineId(String projectName, String pipelineName) {
        invokeCommand(projectName, "select id from cd_pipeline where name=\'$pipelineName\';")[2]
    }

    ArrayList getCdPipelines(String projectName) {
        return getEntries(projectName, "select name from cd_pipeline;")
    }

    ArrayList getCdPipelineApplications(String projectName, String pipelineName, boolean isV2) {
        String command
        if (isV2) {
            command = """
            select c.name from cd_pipeline cp
            left join cd_pipeline_docker_stream cpds on cp.id = cpds.cd_pipeline_id
            left join codebase_docker_stream cds on cpds.codebase_docker_stream_id = cds.id
            left join codebase_branch cb on cds.codebase_branch_id = cb.id
            left join codebase c on cb.codebase_id = c.id
            where cp.name = '${pipelineName}';
            """
        } else {
            command = """
            select c.name from cd_pipeline cp
            left join cd_pipeline_codebase_branch cpcb on cp.id = cpcb.cd_pipeline_id
            left join codebase_branch cb on cpcb.codebase_branch_id = cb.id
            left join codebase c on cb.codebase_id = c.id
            where cp.name = '${pipelineName}';
            """
        }
        return getEntries(projectName, command)
    }

    void removeCdPipeline(String projectName, String pipelineName, boolean isV2) {
        String pipelineId = getPipelineId(projectName, pipelineName)

        ArrayList databaseListCommand = [
                "delete from cd_pipeline_action_log where cd_pipeline_id =\'$pipelineId\';",
                "delete from cd_pipeline_third_party_service where cd_pipeline_id =\'$pipelineId\';",
                "delete from cd_pipeline where id =\'$pipelineId\';",
        ]

        if (isV2)
            databaseListCommand.removeAt(0) // cd_pipeline_codebase_branch does not exist since 2.1.0

        script.println "Removing entries related to CD pipeline \"${pipelineName}\""

        databaseListCommand.each { command ->
            invokeCommand(projectName, command)
        }
    }

    /* CODEBASE BRANCH RELATED REQUESTS */

    ArrayList getCodebaseBranches(String projectName, String codebaseName) {
        String codebaseId = getCodebaseId(projectName, codebaseName)
        return getEntries(projectName, "select name from codebase_branch where codebase_id=\'$codebaseId\';")
    }

    ArrayList getAutotestsBranchCdPipelines(String projectName, String codebaseName, String codebaseBranch, boolean isV2) {
        String codebaseId = getCodebaseId(projectName, codebaseName)
        String command

        if (isV2) {
            command = """
            select cp.name 
            from codebase_branch cb
                left join quality_gate_stage qgs on cb.id = qgs.codebase_branch_id
                left join cd_stage cs on qgs.cd_stage_id = cs.id
                right join cd_pipeline cp on cs.cd_pipeline_id = cp.id
            where cb.name = '${codebaseBranch}' and cb.codebase_id = '${codebaseId}' and qgs.quality_gate = 'autotests';
            """
        } else {
            command = """
            select cp.name
            from codebase_branch cb
                left join cd_stage_codebase_branch cscb on cscb.codebase_branch_id = cb.id
                left join cd_stage cs on cscb.cd_stage_id = cs.id
                right join cd_pipeline cp on cs.cd_pipeline_id = cp.id
            where cb.name = '${codebaseBranch}' and cb.codebase_id = '${codebaseId}';
            """
        }
        return getEntries(projectName, command)
    }

    ArrayList getLibraryBranchCdPipelines(String projectName, String codebaseName, String codebaseBranch, boolean isV2) {
        String codebaseId = getCodebaseId(projectName, codebaseName)
        String command

        if (isV2) {
            command = """
            select cp.name 
            from codebase_branch cb
                left join cd_stage cs on cb.codebase_id = cs.id
                right join cd_pipeline cp on cs.cd_pipeline_id = cp.id
            where cb.name = '${codebaseBranch}' and cb.codebase_id = '${codebaseId}';
            """
        } else {
            command = """
            select cp.name 
            from codebase_branch cb
                left join cd_stage cs on cb.codebase_id = cs.id
                right join cd_pipeline cp on cs.cd_pipeline_id = cp.id
            where cb.name = '${codebaseBranch}' and cb.codebase_id = '${codebaseId}';
            """
        }
        return getEntries(projectName, command)
    }

    ArrayList getApplicationBranchCdPipelines(String projectName, String codebaseName, String codebaseBranch, boolean isV2) {
        String codebaseId = getCodebaseId(projectName, codebaseName)
        String command

        if (isV2) {
            command = """
            select cp.name 
            from codebase_branch cb
                left join codebase_docker_stream cds on cb.id = cds.codebase_branch_id
                left join cd_pipeline_docker_stream cpds on cds.id = cpds.codebase_docker_stream_id
                right join cd_pipeline cp on cpds.cd_pipeline_id = cp.id
            where cb.name = '${codebaseBranch}' and cb.codebase_id = '${codebaseId}';
            """
        } else {
            command = """
            select cp.name
            from codebase_branch cb
                left join cd_pipeline_codebase_branch cpcb on cpcb.codebase_branch_id = cb.id
                right join cd_pipeline cp on cpcb.cd_pipeline_id = cp.id
            where cb.name = '${codebaseBranch}' and cb.codebase_id = '${codebaseId}';
            """
        }
        return getEntries(projectName, command)
    }

    void removeCodebaseBranch(String projectName, String codebaseName, String branchName, boolean isV2) {
        String codebaseId = getCodebaseId(projectName, codebaseName)
        String branchId = invokeCommand(projectName, "select id from codebase_branch where name=\'$branchName\' and codebase_id=\'$codebaseId\';")[2]

        ArrayList databaseListCommand

        if (isV2) {
            databaseListCommand = [
                    "update codebase_docker_stream set codebase_branch_id = null where codebase_branch_id = \'$branchId\';",
                    "update codebase_branch set output_codebase_docker_stream_id = null where id = \'$branchId\';",
                    "delete from codebase_branch where id = \'$branchId\';",
                    "delete from codebase_docker_stream where codebase_branch_id is null;",
            ]
        } else {
            databaseListCommand = [
                    "delete from codebase_branch_action_log where codebase_branch_id = \'$branchId\';",
                    "delete from codebase_branch where id = \'$branchId\';",
            ]
        }

        script.println "Removing entries related to codebase branch \"${branchName}\""

        databaseListCommand.each { command ->
            invokeCommand(projectName, command)
        }
    }

    /* CODEBASE RELATED REQUESTS */

    String getCodebaseId(String projectName, String codebaseName) {
        invokeCommand(projectName, "select id from codebase where name=\'$codebaseName\';")[2]
    }

    String getCodebaseType(String projectName, String codebaseName) {
        invokeCommand(projectName, "select type from codebase where name=\'$codebaseName\';")[2]
    }

    ArrayList getCodebases(String projectName) {
        return getEntries(projectName, "select name from codebase;")
    }

    void removeCodebase(String projectName, String codebaseName, boolean isV2) {
        String codebaseId = getCodebaseId(projectName, codebaseName)

        ArrayList databaseListCommand = [
                "delete from codebase_action_log where codebase_id = \'$codebaseId\';",
                "delete from applications_to_promote where codebase_id = \'$codebaseId\';",
                "delete from codebase where id = \'$codebaseId\';",
        ]

        if (isV2)
            databaseListCommand.removeAt(1)

        databaseListCommand.each { command ->
            invokeCommand(projectName, command)
        }
    }

    ArrayList getAutotestsCdPipelines(String projectName, String codebaseName, boolean isV2) {
        String command

        if (isV2) {
            command = """
            select cp.name
            from codebase c
                left join codebase_branch cb on c.id = cb.codebase_id
                left join quality_gate_stage qgs on cb.id = qgs.codebase_branch_id
                left join cd_stage cs on qgs.cd_stage_id = cs.id
                right join cd_pipeline cp on cs.cd_pipeline_id = cp.id
            where c.name = '${codebaseName}' and qgs.quality_gate = 'autotests';
            """
        } else {
            command = """
            select cp.name
            from codebase c
                left join codebase_branch cb on c.id = cb.codebase_id
                left join cd_stage_codebase_branch cscb on cscb.codebase_branch_id = cb.id
                left join cd_stage cs on cscb.cd_stage_id = cs.id
                right join cd_pipeline cp on cs.cd_pipeline_id = cp.id
            where c.name = '${codebaseName}';
            """
        }
        return getEntries(projectName, command)
    }

    ArrayList getLibraryCdPipelines(String projectName, String codebaseName, boolean isV2) {
        String command

        if (isV2) {
            command = """
            select cp.name
            from codebase c
                left join codebase_branch cb on c.id = cb.codebase_id
                left join cd_stage cs on cb.codebase_id = cs.codebase_branch_id
                right join cd_pipeline cp on cs.cd_pipeline_id = cp.id
            where c.name = '${codebaseName}';
            """
        } else {
            command = """
            select cp.name
            from codebase c
                left join codebase_branch cb on c.id = cb.codebase_id
                left join cd_stage cs on cb.codebase_id = cs.codebase_branch_id
                right join cd_pipeline cp on cs.cd_pipeline_id = cp.id
            where c.name = '${codebaseName}';
            """
        }
        return getEntries(projectName, command)
    }

    ArrayList getApplicationCdPipelines(String projectName, String codebaseName, boolean isV2) {
        String command
        if (isV2) {
            command = """
            select cp.name 
            from codebase c
                left join codebase_branch cb on c.id = cb.codebase_id
                left join codebase_docker_stream cds on cb.id = cds.codebase_branch_id
                left join cd_pipeline_docker_stream cpds on cds.id = cpds.codebase_docker_stream_id
                right join cd_pipeline cp on cpds.cd_pipeline_id = cp.id
            where c.name = '${codebaseName}';            
            """
        } else {
            command = """
            select cp.name
            from codebase c
                left join codebase_branch cb on c.id = cb.codebase_id
                left join cd_pipeline_codebase_branch cpcb on cpcb.codebase_branch_id = cb.id
                right join cd_pipeline cp on cpcb.cd_pipeline_id = cp.id
            where c.name = '${codebaseName}';
            """
        }
        return getEntries(projectName, command)
    }
}
