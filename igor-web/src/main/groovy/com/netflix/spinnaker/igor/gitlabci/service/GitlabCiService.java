/*
 * Copyright 2017 Netflix, Inc.
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

import com.netflix.spinnaker.fiat.model.resources.Permissions;
import com.netflix.spinnaker.igor.build.model.GenericBuild;
import com.netflix.spinnaker.igor.build.model.GenericGitRevision;
import com.netflix.spinnaker.igor.build.model.JobConfiguration;
import com.netflix.spinnaker.igor.gitlabci.client.GitlabCiClient;
import com.netflix.spinnaker.igor.gitlabci.client.model.Pipeline;
import com.netflix.spinnaker.igor.gitlabci.client.model.Project;
import com.netflix.spinnaker.igor.model.BuildServiceProvider;
import com.netflix.spinnaker.igor.service.BuildOperations;
import com.netflix.spinnaker.security.AuthenticatedRequest;
import org.springframework.web.client.HttpClientErrorException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class GitlabCiService implements BuildOperations {
  private final String name;
  private final GitlabCiClient client;
  private final String address;
  private final boolean limitByMembership;
  private final boolean limitByOwnership;
  private final Permissions permissions;

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
  public GenericBuild getGenericBuild(String job, int buildNumber) {
    throw new UnsupportedOperationException();
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
