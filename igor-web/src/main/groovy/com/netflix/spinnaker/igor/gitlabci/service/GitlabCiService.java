/*
 * Copyright 2017 Netflix, Inc.
 * Copyright 2022 Redbox Entertainment, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.spinnaker.igor.gitlabci.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.netflix.spinnaker.fiat.model.resources.Permissions;
import com.netflix.spinnaker.igor.build.model.GenericBuild;
import com.netflix.spinnaker.igor.build.model.GenericGitRevision;
import com.netflix.spinnaker.igor.build.model.JobConfiguration;
import com.netflix.spinnaker.igor.build.model.Result;
import com.netflix.spinnaker.igor.gitlabci.client.GitlabCiClient;
import com.netflix.spinnaker.igor.gitlabci.client.model.*;
import com.netflix.spinnaker.igor.model.BuildServiceProvider;
import com.netflix.spinnaker.igor.service.BuildOperations;
import com.netflix.spinnaker.igor.service.BuildProperties;
import com.netflix.spinnaker.igor.travis.client.logparser.PropertyParser;
import com.netflix.spinnaker.kork.core.RetrySupport;
import com.netflix.spinnaker.kork.exceptions.SpinnakerException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import retrofit.RetrofitError;
import retrofit.client.Response;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

import static net.logstash.logback.argument.StructuredArguments.kv;

@Slf4j
public class GitlabCiService implements BuildOperations, BuildProperties {
  private final String name;
  private final GitlabCiClient client;
  private final String address;
  private final boolean limitByMembership;
  private final boolean limitByOwnership;
  private final Permissions permissions;
  private final RetrySupport retrySupport = new RetrySupport();
  private final ObjectMapper objectMapper = new ObjectMapper();

  public GitlabCiService(
      GitlabCiClient client,
      String name,
      String address,
      boolean limitByMembership,
      boolean limitByOwnership,
      Permissions permissions) {
    this.client = client;
    this.name = name;
    this.address = address;
    this.limitByMembership = limitByMembership;
    this.limitByOwnership = limitByOwnership;
    this.permissions = permissions;
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public BuildServiceProvider getBuildServiceProvider() {
    return BuildServiceProvider.GITLAB_CI;
  }

  @Override
  public List<GenericGitRevision> getGenericGitRevisions(String job, GenericBuild build) {
    throw new UnsupportedOperationException();
  }

  @Override
  public GenericBuild getGenericBuild(String projectId, int pipelineId) {
    Pipeline pipeline = client.getPipeline(projectId, pipelineId);
    if (pipeline == null) {
      return null;
    }
    return pipeline.toGenericBuild();
  }

  @Override
  public List<Pipeline> getBuilds(String job) {
    return this.client.getPipelineSummaries(job, 25);
  }

  @Override
  public JobConfiguration getJobConfig(String jobName) {
    throw new UnsupportedOperationException("getJobConfig is not yet implemented for Gitlab CI");
  }

  @Override
  public int triggerBuildWithParameters(String job, Map<String, String> queryParameters) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Permissions getPermissions() {
    return permissions;
  }

  public List<Project> getProjects() {
    return getProjectsRec(new ArrayList<>(), 1, 100)
      .parallelStream()
      // Ignore projects that don't have Gitlab CI enabled.  It is not possible to filter this using the GitLab
      // API. We need to filter it after retrieving all projects
      .filter(project -> project.getBuildsAccessLevel().equals("enabled")).collect(Collectors.toList());
  }

  @Override
  public Map<String, Object> getBuildProperties(String job, GenericBuild build, String fileName) {
    if (StringUtils.isEmpty(fileName)) {
      return new HashMap<>();
    }

    return this.getPropertyFileFromLog(job, build.getNumber());
  }

  // Gets a pipeline's jobs along with any child pipeline jobs (bridges)
  private List<Job> getJobsWithBridges(String projectId, Integer pipelineId) {
    List<Job> jobs = this.client.getJobs(projectId, pipelineId);
    List<Bridge> bridges = this.client.getBridges(projectId, pipelineId);
    bridges.parallelStream()
      .filter(bridge -> {
        // Filter out any child pipelines that failed or are still in-progress
        Pipeline parent = bridge.getDownstreamPipeline();
        return parent != null && GitlabCiResultConverter.getResultFromGitlabCiState(parent.getStatus()) == Result.SUCCESS;
      })
      .forEach(bridge -> {
      jobs.addAll(this.client.getJobs(projectId, bridge.getDownstreamPipeline().getId()));
    });
    return jobs;
  }

  private Map<String,Object> getPropertyFileFromLog(String projectId, Integer pipelineId) {
    Map<String, Object> properties = new HashMap<>();
    return retrySupport.retry(
      () -> {
        try {
          Pipeline pipeline = this.client.getPipeline(projectId, pipelineId);
          PipelineStatus status = pipeline.getStatus();
          if (status != PipelineStatus.running) {
            log.error(
              "Unable to get GitLab build properties, pipeline '{}' in project '{}' has status {}",
              kv("pipeline", pipelineId),
              kv("project", projectId),
              kv("status", status));
          }
          // Pipeline has many jobs, jobs have many artifacts
          // No way to list all job's artifacts so loop the jobs
          // trying to find the artifact.  Return if/when one is found
          List<Job> jobs = getJobsWithBridges(projectId, pipelineId);
          for (Job job : jobs) {
            InputStream logStream = this.client.getJobLog(projectId, job.getId()).getBody().in();
            String log = new String(logStream.readAllBytes(), StandardCharsets.UTF_8);
            Map<String, Object> jobProperties = PropertyParser.extractPropertiesFromLog(log);
            properties.putAll(jobProperties);
          }

          return properties;

        } catch (RetrofitError e) {
          // retry on network issue, 404 and 5XX
          if (e.getKind() == RetrofitError.Kind.NETWORK
            || (e.getKind() == RetrofitError.Kind.HTTP
            && (e.getResponse().getStatus() == 404
            || e.getResponse().getStatus() >= 500))) {
            throw e;
          }
          SpinnakerException ex = new SpinnakerException(e);
          ex.setRetryable(false);
          throw ex;
        } catch (IOException e) {
          log.error("Error while parsing GitLab CI log to build properties", e);
          return properties;
        }
      },
      5,
      Duration.ofSeconds(2),
      false);
  }

  public List<Pipeline> getPipelines(final Project project, int limit) {
    isValidPageSize(limit);

    return client.getPipelineSummaries(String.valueOf(project.getId()), limit);
  }

  public String getAddress() {
    return address;
  }

  private List<Project> getProjectsRec(List<Project> projects, int page, int pageSize) {
    isValidPageSize(pageSize);
    List<Project> slice = client.getProjects(limitByMembership, limitByOwnership, page, pageSize);
    if (slice.isEmpty()) {
      return projects;
    } else {
      projects.addAll(slice);
      return getProjectsRec(projects, page + 1, pageSize);
    }
  }

  private static void isValidPageSize(int perPage) {
    if (perPage > GitlabCiClient.MAX_PAGE_SIZE) {
      throw new IllegalArgumentException(
          "Gitlab API call page size should be no more than "
              + GitlabCiClient.MAX_PAGE_SIZE
              + " but was "
              + perPage);
    }
  }
}
