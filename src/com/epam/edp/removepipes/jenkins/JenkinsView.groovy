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

package com.epam.edp.removepipes.jenkins

import jenkins.model.*

class JenkinsView extends JenkinsItem {

    String applicationName

    JenkinsView(String applicationName, String viewName, Script script) {
        super(viewName, script)
        this.applicationName = applicationName
    }

    void remove() {
        try {
            def view = Jenkins.instance.getItemByFullName(applicationName).getView(name)
            view.owner.deleteView(view)
            script.println("Jenkins view \"$name\" has been removed")
        } catch (any) {
            script.println("WARNING: Jenkins view \"$name\" cannot be found")
        }
    }
}