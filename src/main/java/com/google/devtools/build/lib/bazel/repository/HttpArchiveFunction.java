// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.bazel.repository;

import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.analysis.RuleDefinition;
import com.google.devtools.build.lib.bazel.repository.downloader.HttpDownloader;
import com.google.devtools.build.lib.bazel.rules.workspace.HttpArchiveRule;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.rules.repository.RepositoryDirectoryValue;
import com.google.devtools.build.lib.rules.repository.RepositoryFunction;
import com.google.devtools.build.lib.rules.repository.WorkspaceAttributeMapper;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.Type;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.skyframe.SkyFunction.Environment;
import com.google.devtools.build.skyframe.SkyFunctionException.Transience;
import com.google.devtools.build.skyframe.SkyValue;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Downloads a file over HTTP.
 */
public class HttpArchiveFunction extends RepositoryFunction {

  protected final AtomicReference<HttpDownloader> httpDownloader;

  public HttpArchiveFunction(AtomicReference<HttpDownloader> httpDownloader) {
    this.httpDownloader = httpDownloader;
  }

  @Override
  public boolean isLocal(Rule rule) {
    return false;
  }

  protected void createDirectory(Path path)
      throws RepositoryFunctionException {
    try {
      FileSystemUtils.createDirectoryAndParents(path);
    } catch (IOException e) {
      throw new RepositoryFunctionException(e, Transience.TRANSIENT);
    }
  }

  @Override
  public SkyValue fetch(
      Rule rule, Path outputDirectory, BlazeDirectories directories, Environment env)
          throws RepositoryFunctionException, InterruptedException {
    // The output directory is always under output_base/external (to stay out of the way of
    // artifacts from this repository) and uses the rule's name to avoid conflicts with other
    // remote repository rules. For example, suppose you had the following WORKSPACE file:
    //
    // http_archive(name = "png", url = "http://example.com/downloads/png.tar.gz", sha256 = "...")
    //
    // This would download png.tar.gz to output_base/external/png/png.tar.gz.
    createDirectory(outputDirectory);
    Path downloadedPath = httpDownloader.get().download(rule, outputDirectory,
        env.getListener(), clientEnvironment);

    DecompressorValue.decompress(getDescriptor(rule, downloadedPath, outputDirectory));
    return RepositoryDirectoryValue.create(outputDirectory);
  }

  protected DecompressorDescriptor getDescriptor(Rule rule, Path downloadPath, Path outputDirectory)
      throws RepositoryFunctionException {
    DecompressorDescriptor.Builder builder = DecompressorDescriptor.builder()
        .setTargetKind(rule.getTargetKind())
        .setTargetName(rule.getName())
        .setArchivePath(downloadPath)
        .setRepositoryPath(outputDirectory);
    WorkspaceAttributeMapper mapper = WorkspaceAttributeMapper.of(rule);
    if (mapper.isAttributeValueExplicitlySpecified("strip_prefix")) {
      try {
        builder.setPrefix(mapper.get("strip_prefix", Type.STRING));
      } catch (EvalException e) {
        throw new RepositoryFunctionException(e, Transience.PERSISTENT);
      }
    }
    return builder.build();
  }

  @Override
  public Class<? extends RuleDefinition> getRuleDefinition() {
    return HttpArchiveRule.class;
  }
}
