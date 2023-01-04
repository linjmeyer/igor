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

import static java.lang.Thread.sleep;
import static net.logstash.logback.argument.StructuredArguments.kv;

import com.netflix.spinnaker.fiat.model.resources.Permissions;
import com.netflix.spinnaker.igor.build.model.GenericBuild;
import com.netflix.spinnaker.igor.build.model.GenericGitRevision;
import com.netflix.spinnaker.igor.build.model.JobConfiguration;
import com.netflix.spinnaker.igor.build.model.Result;
import com.netflix.spinnaker.igor.config.GitlabCiProperties;
import com.netflix.spinnaker.igor.gitlabci.client.GitlabCiClient;
import com.netflix.spinnaker.igor.gitlabci.client.model.*;
import com.netflix.spinnaker.igor.model.BuildServiceProvider;
import com.netflix.spinnaker.igor.service.BuildOperations;
import com.netflix.spinnaker.igor.service.BuildProperties;
import com.netflix.spinnaker.igor.travis.client.logparser.PropertyParser;
import com.netflix.spinnaker.kork.core.RetrySupport;
import com.netflix.spinnaker.kork.exceptions.SpinnakerException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import retrofit.RetrofitError;
import retrofit.client.Header;

@Slf4j
public class GitlabCiService implements BuildOperations, BuildProperties {
  private final String name;
  private final GitlabCiClient client;
  private final GitlabCiProperties.GitlabCiHost hostConfig;
  private final Permissions permissions;
  private final RetrySupport retrySupport = new RetrySupport();

  public GitlabCiService(
      GitlabCiClient client,
      String name,
      GitlabCiProperties.GitlabCiHost hostConfig,
      Permissions permissions) {
    this.client = client;
    this.name = name;
    this.hostConfig = hostConfig;
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
    Project project = retryWithRateLimitingBackoff(() -> client.getProject(projectId));
    if (project == null) {
      log.error("Could not find Gitlab CI Project with projectId={}", projectId);
      return null;
    }
    Pipeline pipeline = retryWithRateLimitingBackoff(() -> client.getPipeline(projectId, pipelineId));
    if (pipeline == null) {
      return null;
    }
    return GitlabCiPipelineUtils.genericBuild(
        pipeline, this.hostConfig.getAddress(), project.getPathWithNamespace());
  }

  @Override
  public List<Pipeline> getBuilds(String job) {
    return retryWithRateLimitingBackoff(() -> this.client.getPipelineSummaries(job, this.hostConfig.getDefaultHttpPageLength()));
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
    return getProjectsRec(new ArrayList<>(), 1).parallelStream()
        // Ignore projects that don't have Gitlab CI enabled.  It is not possible to filter this
        // using the GitLab
        // API. We need to filter it after retrieving all projects
        .filter(project -> project.getBuildsAccessLevel().equals("enabled"))
        .collect(Collectors.toList());
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
    List<Job> jobs = retryWithRateLimitingBackoff(() -> this.client.getJobs(projectId, pipelineId));
    List<Bridge> bridges = retryWithRateLimitingBackoff(() -> this.client.getBridges(projectId, pipelineId));
    bridges.parallelStream()
        .filter(
            bridge -> {
              // Filter out any child pipelines that failed or are still in-progress
              Pipeline parent = bridge.getDownstreamPipeline();
              return parent != null
                  && GitlabCiResultConverter.getResultFromGitlabCiState(parent.getStatus())
                      == Result.SUCCESS;
            })
        .forEach(
            bridge -> {
              List<Job> allJobsForBridge = retryWithRateLimitingBackoff(() -> this.client.getJobs(projectId, bridge.getDownstreamPipeline().getId()));
                jobs.addAll(allJobsForBridge);
            });
    return jobs;
  }

  private <T> T retryWithRateLimitingBackoff(Supplier<T> fn) {
    return retrySupport.retry(
      () -> {
        try {
          return fn.get();
        } catch (RetrofitError retrofitError) {
          // default retry on network issue
          if (retrofitError.getKind() == RetrofitError.Kind.NETWORK) {
            throw retrofitError;
          }
          if (retrofitError.getKind() == RetrofitError.Kind.HTTP) {
            int status = retrofitError.getResponse().getStatus();
            // default retry for 404s and 500s (Gitlab API may return 404s on newly created objects)
            if (status == 404 || status >= 500) {
              throw retrofitError;
            }
            // 429 (rate limit) we can check for a header that tells us how long to wait before proceeding
            // https://docs.gitlab.com/ee/user/admin_area/settings/user_and_ip_rate_limits.html#response-headers
            if (status == 429) {
              try {
                var retryAfterSeconds = Long.parseLong(retrofitError.getResponse()
                  .getHeaders()
                  .stream()
                  .filter(h -> h.getName().equalsIgnoreCase("Retry-After"))
                  .map(Header::getValue)
                  .findFirst()
                  .orElse(""));
                log.warn("Waiting {} seconds using GitlabCI Retry-After header due to rate limiting", retryAfterSeconds);
                try {
                  Thread.sleep(retryAfterSeconds * 1000);
                } catch (InterruptedException ignored) {
                }
                throw retrofitError; // Rethrow after 429 response header wait to conduct another retry
              } catch (NumberFormatException headerParseException) {
                log.error("Failed to parse Retry-After header during GitlabCI rate limiting", headerParseException);
                throw retrofitError;
              }
            }
          }

          // Any other exceptions do not retry
          SpinnakerException ex = new SpinnakerException(retrofitError);
          ex.setRetryable(false);
          throw ex;
        }
      },
      this.hostConfig.getHttpRetryMaxAttempts(),
      Duration.ofSeconds(this.hostConfig.getHttpRetryWaitSeconds()),
      this.hostConfig.getHttpRetryExponentialBackoff()
    );
  }

  private Map<String, Object> getPropertyFileFromLog(String projectId, Integer pipelineId) {
    Map<String, Object> properties = new HashMap<>();
        try {
          Pipeline pipeline = retryWithRateLimitingBackoff(() -> this.client.getPipeline(projectId, pipelineId));
          PipelineStatus status = pipeline.getStatus();
          if (status != PipelineStatus.running) {
            log.error(
                "Unable to get GitLab build properties, pipeline '{}' in project '{}' has status {}",
                kv("pipeline", pipelineId),
                kv("project", projectId),
                kv("status", status));
          }
          // Pipelines logs are stored within each stage (job), loop all jobs of this pipeline
          // and any jobs of child pipeline's to parse all logs for the pipeline
          List<Job> jobs = getJobsWithBridges(projectId, pipelineId);
          for (Job job : jobs) {
            InputStream logStream = retryWithRateLimitingBackoff(() -> this.client.getJobLog(projectId, job.getId())).getBody().in();
            String log = new String(logStream.readAllBytes(), StandardCharsets.UTF_8);
            Map<String, Object> jobProperties = PropertyParser.extractPropertiesFromLog(log);
            properties.putAll(jobProperties);
          }
          return properties;
        } catch (IOException e) {
          log.error("Error while parsing GitLab CI log to build properties", e);
          return properties;
        }
  }

  public List<Pipeline> getPipelines(final Project project, int pageSize) {
    return retryWithRateLimitingBackoff(() -> client.getPipelineSummaries(String.valueOf(project.getId()), pageSize));
  }

  public String getAddress() {
    return this.hostConfig.getAddress();
  }

  private List<Project> getProjectsRec(List<Project> projects, int page) {
    List<Project> slice = retryWithRateLimitingBackoff(() ->
      client.getProjects(
        hostConfig.getLimitByMembership(),
        hostConfig.getLimitByOwnership(),
        page,
        hostConfig.getDefaultHttpPageLength()));
    if (slice.isEmpty()) {
      return projects;
    } else {
      projects.addAll(slice);
      return getProjectsRec(projects, page + 1);
    }
  }
}
