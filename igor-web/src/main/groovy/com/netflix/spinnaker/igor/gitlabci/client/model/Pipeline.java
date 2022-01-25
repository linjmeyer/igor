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
package com.netflix.spinnaker.igor.gitlabci.client.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.netflix.spinnaker.igor.build.model.GenericBuild;
import com.netflix.spinnaker.igor.gitlabci.service.GitlabCiResultConverter;
import java.util.Date;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Pipeline {
  private int id;
  private String sha;
  private String ref;
  private PipelineStatus status;
  private boolean tag;
  private int duration;

  @JsonProperty("created_at")
  private Date createdAt;

  public Date getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Date createdAt) {
    this.createdAt = createdAt;
  }

  public int getId() {
    return id;
  }

  public void setId(int id) {
    this.id = id;
  }

  public String getSha() {
    return sha;
  }

  public void setSha(String sha) {
    this.sha = sha;
  }

  public String getRef() {
    return ref;
  }

  public void setRef(String ref) {
    this.ref = ref;
  }

  public PipelineStatus getStatus() {
    return status;
  }

  public void setStatus(PipelineStatus status) {
    this.status = status;
  }

  public GenericBuild toGenericBuild() {
    // ToDo: Add additional properties
    GenericBuild genericBuild = new GenericBuild();
    genericBuild.setBuilding(GitlabCiResultConverter.running(this.getStatus()));
    genericBuild.setNumber(this.getId());
    genericBuild.setResult(GitlabCiResultConverter.getResultFromGitlabCiState(this.getStatus()));
    genericBuild.setName(String.valueOf(this.getId()));
    genericBuild.setId(String.valueOf(this.getId()));
    return genericBuild;
  }
}
