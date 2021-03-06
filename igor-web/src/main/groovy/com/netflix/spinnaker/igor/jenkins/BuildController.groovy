/*
 * Copyright 2015 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.igor.jenkins

import com.fasterxml.jackson.databind.ObjectMapper
import com.netflix.spinnaker.igor.jenkins.client.JenkinsMasters
import com.netflix.spinnaker.igor.jenkins.client.model.Build
import com.netflix.spinnaker.igor.jenkins.client.model.JobConfig
import com.netflix.spinnaker.igor.jenkins.client.model.QueuedJob
import groovy.transform.InheritConstructors
import groovy.util.logging.Slf4j
import org.springframework.web.bind.annotation.ExceptionHandler
import retrofit.RetrofitError
import javax.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.HandlerMapping
import org.yaml.snakeyaml.Yaml
import javax.servlet.http.HttpServletResponse
import java.util.concurrent.ExecutorService

@Slf4j
@RestController
class BuildController {
    @Autowired
    JenkinsMasters masters

    @Autowired
    ExecutorService executor

    @Autowired
    ObjectMapper objectMapper

    @RequestMapping(value = '/builds/status/{buildNumber}/{master}/**')
    Map getJobStatus(@PathVariable String master, @PathVariable Integer buildNumber, HttpServletRequest request) {
        def job = (String) request.getAttribute(
            HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE).split('/').drop(5).join('/')
        if (!masters.map.containsKey(master)) {
            throw new MasterNotFoundException("Master '${master}' not found")
        }
        Map result = objectMapper.convertValue(masters.map[master].getBuild(job, buildNumber), Map)
        try {
            Map scm = objectMapper.convertValue(masters.map[master].getGitDetails(job, buildNumber), Map)
            if (scm?.action?.lastBuiltRevision?.branch?.name) {
                result.scm = scm?.action.lastBuiltRevision
                result.scm = result.scm.branch.collect {
                    it.branch = it.name.split('/').last()
                    it
                }

            }
        } catch (Exception e) {
            log.error("could not get scm results for $master / $job / $buildNumber")
        }
        result
    }

    @RequestMapping(value = '/builds/queue/{master}/{item}')
    QueuedJob getQueueLocation(@PathVariable String master, @PathVariable int item){
        if (!masters.map.containsKey(master)) {
            throw new MasterNotFoundException("Master '${master}' not found")
        }
        try {
            return masters.map[master].getQueuedItem(item)
        } catch (RetrofitError e) {
            if (e.response?.status == HttpStatus.NOT_FOUND.value()) {
              throw new QueuedJobNotFoundException("Queued job '${item}' not found for master '${master}'.")
            }
            throw e
        }
    }

    @RequestMapping(value = '/builds/all/{master}/**')
    List<Build> getBuilds(@PathVariable String master, HttpServletRequest request) {
        def job = (String) request.getAttribute(
            HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE).split('/').drop(4).join('/')
        if (!masters.map.containsKey(master)) {
            throw new MasterNotFoundException("Master '${master}' not found")
        }
        masters.map[master].getBuilds(job).list
    }

    @RequestMapping(value = '/masters/{name}/jobs/**', method = RequestMethod.PUT)
    String build(
        @PathVariable("name") String master,
        @RequestParam Map<String, String> requestParams, HttpServletRequest request) {
        def job = (String) request.getAttribute(
            HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE).split('/').drop(4).join('/')
        if (!masters.map.containsKey(master)) {
            throw new MasterNotFoundException("Master '${master}' not found")
        }

        def response
        def jenkinsService = masters.map[master]
        JobConfig jobConfig = jenkinsService.getJobConfig(job)

        if (jobConfig.parameterDefinitionList?.size() > 0) {
            validateJobParameters(jobConfig, requestParams)
        }
        if (requestParams && jobConfig.parameterDefinitionList?.size() > 0) {
            response = jenkinsService.buildWithParameters(job, requestParams)
        } else if (!requestParams && jobConfig.parameterDefinitionList?.size() > 0) {
            // account for when you just want to fire a job with the default parameter values by adding a dummy param
            response = jenkinsService.buildWithParameters(job, ['startedBy': "igor"])
        } else if (!requestParams && (!jobConfig.parameterDefinitionList || jobConfig.parameterDefinitionList.size() == 0)) {
            response = jenkinsService.build(job)
        } else { // Jenkins will reject the build, so don't even try
            // we should throw a BuildJobError, but I get a bytecode error : java.lang.VerifyError: Bad <init> method call from inside of a branch
            throw new RuntimeException("job : ${job}, passing params to a job which doesn't need them")
        }

        if (response.status != 201) {
            throw new BuildJobError("Received a non-201 status when submitting job '${job}' to master '${master}'")
        }

        log.info("Submitted build job `${job}`")
        def locationHeader = response.headers.find { it.name == "Location" }
        if (!locationHeader) {
            throw new QueuedJobDeterminationError("Could not find Location header for job '${job}'")
        }
        def queuedLocation = locationHeader.value

        queuedLocation.split('/')[-1]
    }

    static void validateJobParameters(JobConfig jobConfig, Map<String, String> requestParams) {
        jobConfig.parameterDefinitionList.each { parameterDefinition ->
            String matchingParam = requestParams[parameterDefinition.name]
            if (matchingParam != null && parameterDefinition.type == 'ChoiceParameterDefinition' && !parameterDefinition.choices.contains(matchingParam)) {
                throw new InvalidJobParameterException("`${matchingParam}` is not a valid choice " +
                    "for `${parameterDefinition.name}`. Valid choices are: ${parameterDefinition.choices.join(', ')}")
            }
        }
    }

    @RequestMapping(value = '/builds/properties/{buildNumber}/{fileName}/{master}/**')
    Map<String, Object> getProperties(
        @PathVariable String master,
        @PathVariable Integer buildNumber, @PathVariable String fileName, HttpServletRequest request) {
        def job = (String) request.getAttribute(
            HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE).split('/').drop(6).join('/')
        if (!masters.map.containsKey(master)) {
            throw new MasterNotFoundException("Could not find master '${master}' to get properties")
        }
        Map<String, Object> map = [:]
        try {
            def jenkinsService = masters.map[master]
            String path = jenkinsService.getBuild(job, buildNumber).artifacts.find {
                it.fileName == fileName
            }?.relativePath

            def propertyStream = jenkinsService.getPropertyFile(job, buildNumber, path).body.in()

            if (fileName.endsWith('.yml') || fileName.endsWith('.yaml')) {
                Yaml yml = new Yaml()
                map = yml.load(propertyStream)
            } else if (fileName.endsWith('.json')) {
                map = objectMapper.readValue(propertyStream, Map)
            } else {
                Properties properties = new Properties()
                properties.load(propertyStream)
                map = map << properties
            }
        } catch (e) {
            log.error("Unable to get properties `${job}`", e)
        }
        map
    }

    @ExceptionHandler(BuildJobError.class)
    void handleBuildJobError(HttpServletResponse response, BuildJobError e) throws IOException {
        log.error(e.getMessage())
        response.sendError(HttpStatus.BAD_REQUEST.value(), e.getMessage())
    }

    @ExceptionHandler(InvalidJobParameterException.class)
    void handleInvalidJobParameterException(HttpServletResponse response, InvalidJobParameterException e) throws IOException {
        log.error(e.getMessage())
        response.sendError(HttpStatus.BAD_REQUEST.value(), e.getMessage())
    }

    @ExceptionHandler(value=[MasterNotFoundException.class,QueuedJobNotFoundException])
    void handleNotFoundException(HttpServletResponse response, Exception e) throws IOException {
        log.error(e.getMessage())
        response.sendError(HttpStatus.NOT_FOUND.value(), e.getMessage())
    }

    @ExceptionHandler(QueuedJobDeterminationError.class)
    void handleServiceUnavailableException(HttpServletResponse response, Exception e) throws IOException {
        log.error(e.getMessage())
        response.sendError(HttpStatus.SERVICE_UNAVAILABLE.value(), e.getMessage())
    }

    @ExceptionHandler(RuntimeException.class)
    void handleOtherException(HttpServletResponse response, Exception e) throws IOException {
        log.error(e.getMessage())
        response.sendError(HttpStatus.INTERNAL_SERVER_ERROR.value(), e.getMessage())
    }

    @InheritConstructors
    static class MasterNotFoundException extends RuntimeException {}

    @InheritConstructors
    static class QueuedJobNotFoundException extends RuntimeException {}

    @InheritConstructors
    static class BuildJobError extends RuntimeException {}

    @InheritConstructors
    static class QueuedJobDeterminationError extends RuntimeException {}

    @InheritConstructors
    static class InvalidJobParameterException extends RuntimeException {}

}
