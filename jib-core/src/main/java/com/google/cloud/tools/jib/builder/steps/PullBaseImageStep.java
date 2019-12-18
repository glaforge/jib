/*
 * Copyright 2018 Google LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.cloud.tools.jib.builder.steps;

import com.google.api.client.http.HttpResponseException;
import com.google.cloud.tools.jib.api.Credential;
import com.google.cloud.tools.jib.api.DescriptorDigest;
import com.google.cloud.tools.jib.api.ImageReference;
import com.google.cloud.tools.jib.api.LogEvent;
import com.google.cloud.tools.jib.api.RegistryException;
import com.google.cloud.tools.jib.api.RegistryUnauthorizedException;
import com.google.cloud.tools.jib.blob.Blobs;
import com.google.cloud.tools.jib.builder.ProgressEventDispatcher;
import com.google.cloud.tools.jib.builder.TimerEventDispatcher;
import com.google.cloud.tools.jib.builder.steps.PullBaseImageStep.ImageAndAuthorization;
import com.google.cloud.tools.jib.cache.CacheCorruptedException;
import com.google.cloud.tools.jib.configuration.BuildContext;
import com.google.cloud.tools.jib.event.EventHandlers;
import com.google.cloud.tools.jib.event.events.ProgressEvent;
import com.google.cloud.tools.jib.http.Authorization;
import com.google.cloud.tools.jib.image.Image;
import com.google.cloud.tools.jib.image.LayerCountMismatchException;
import com.google.cloud.tools.jib.image.LayerPropertyNotFoundException;
import com.google.cloud.tools.jib.image.json.BadContainerConfigurationFormatException;
import com.google.cloud.tools.jib.image.json.BuildableManifestTemplate;
import com.google.cloud.tools.jib.image.json.ContainerConfigurationTemplate;
import com.google.cloud.tools.jib.image.json.JsonToImageTranslator;
import com.google.cloud.tools.jib.image.json.ManifestAndConfig;
import com.google.cloud.tools.jib.image.json.ManifestTemplate;
import com.google.cloud.tools.jib.image.json.UnknownManifestFormatException;
import com.google.cloud.tools.jib.image.json.V21ManifestTemplate;
import com.google.cloud.tools.jib.image.json.V22ManifestListTemplate;
import com.google.cloud.tools.jib.json.JsonTemplateMapper;
import com.google.cloud.tools.jib.registry.ManifestAndDigest;
import com.google.cloud.tools.jib.registry.RegistryAuthenticator;
import com.google.cloud.tools.jib.registry.RegistryClient;
import com.google.cloud.tools.jib.registry.credentials.CredentialRetrievalException;
import com.google.common.annotations.VisibleForTesting;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import javax.annotation.Nullable;

/** Pulls the base image manifest. */
class PullBaseImageStep implements Callable<ImageAndAuthorization> {

  private static final String DESCRIPTION = "Pulling base image manifest";

  /** Structure for the result returned by this step. */
  static class ImageAndAuthorization {

    private final Image image;
    private final @Nullable Authorization authorization;

    @VisibleForTesting
    ImageAndAuthorization(Image image, @Nullable Authorization authorization) {
      this.image = image;
      this.authorization = authorization;
    }

    Image getImage() {
      return image;
    }

    @Nullable
    Authorization getAuthorization() {
      return authorization;
    }
  }

  private final BuildContext buildContext;
  private final ProgressEventDispatcher.Factory progressEventDispatcherFactory;

  PullBaseImageStep(
      BuildContext buildContext, ProgressEventDispatcher.Factory progressEventDispatcherFactory) {
    this.buildContext = buildContext;
    this.progressEventDispatcherFactory = progressEventDispatcherFactory;
  }

  @Override
  public ImageAndAuthorization call()
      throws IOException, RegistryException, LayerPropertyNotFoundException,
          LayerCountMismatchException, BadContainerConfigurationFormatException,
          CacheCorruptedException, CredentialRetrievalException {
    EventHandlers eventHandlers = buildContext.getEventHandlers();
    // Skip this step if this is a scratch image
    ImageReference imageReference = buildContext.getBaseImageConfiguration().getImage();
    if (imageReference.isScratch()) {
      eventHandlers.dispatch(LogEvent.progress("Getting scratch base image..."));
      return new ImageAndAuthorization(Image.builder(buildContext.getTargetFormat()).build(), null);
    }

    eventHandlers.dispatch(
        LogEvent.progress("Getting manifest for base image " + imageReference + "..."));

    if (buildContext.isOffline() || imageReference.isTagDigest()) {
      Optional<Image> image = getCachedBaseImage();
      if (image.isPresent()) {
        return new ImageAndAuthorization(image.get(), null);
      }
      if (buildContext.isOffline()) {
        throw new IOException(
            "Cannot run Jib in offline mode; " + imageReference + " not found in local Jib cache");
      }
    }

    try (ProgressEventDispatcher progressEventDispatcher =
            progressEventDispatcherFactory.create("pulling base image manifest", 2);
        TimerEventDispatcher ignored1 = new TimerEventDispatcher(eventHandlers, DESCRIPTION)) {

      try {
        // First, try with no credentials. This works with public GCR images (but not Docker Hub).
        // TODO: investigate if we should just pass credentials up front. However, this involves
        // some risk. https://github.com/GoogleContainerTools/jib/pull/2200#discussion_r359069026
        // contains some related discussions.
        return new ImageAndAuthorization(pullBaseImage(null, progressEventDispatcher), null);

      } catch (RegistryUnauthorizedException ex) {
        eventHandlers.dispatch(
            LogEvent.lifecycle(
                "The base image requires auth. Trying again for " + imageReference + "..."));

        // TODO: Refactor the logic in RetrieveRegistryCredentialsStep out to
        // registry.credentials.RegistryCredentialsRetriever.
        Credential credentials =
            RetrieveRegistryCredentialsStep.forBaseImage(
                    buildContext, progressEventDispatcher.newChildProducer())
                .call()
                .orElse(null);

        RegistryClient registryClient =
            buildContext.newBaseImageRegistryClientFactory().newRegistryClient();

        // If we reached here, most registries would have returned "WWW-Authenticate: Bearer ...".
        // (Note, a local Docker registry may return "WWW-Authenticate: Basic ...".)
        String wwwAuthenticate = ex.getHttpResponseException().getHeaders().getAuthenticate();
        if (wwwAuthenticate != null) {
          Optional<RegistryAuthenticator> registryAuthenticator =
              registryClient.getRegistryAuthenticator(wwwAuthenticate);
          if (registryAuthenticator.isPresent()) {
            // The registry did return "WWW-Authenticate: Bearer ...". Initiate the Docker Token
            // Authentication flow (https://docs.docker.com/registry/spec/auth/token).
            Authorization authorization = registryAuthenticator.get().authenticatePull(credentials);
            return new ImageAndAuthorization(
                pullBaseImage(authorization, progressEventDispatcher), authorization);
          }
        }

        if (credentials != null && !credentials.isOAuth2RefreshToken()) {
          // A local Docker registry (docker run registry:2) may set up "basic authentication."
          try {
            Authorization authorization =
                Authorization.fromBasicCredentials(
                    credentials.getUsername(), credentials.getPassword());
            return new ImageAndAuthorization(
                pullBaseImage(authorization, progressEventDispatcher), authorization);
          } catch (RegistryUnauthorizedException ignored) {
            // "Basic authentication" didn't work either. Fall through.
          }
        }

        // Last resort. Access "<registry URL>/v2/" and hope the registry will return
        // "WWW-Authenticate: Bearer ...". Perhaps it's rare to reach here.
        RegistryAuthenticator registryAuthenticator =
            registryClient.getRegistryAuthenticator().orElseThrow(() -> ex);
        Authorization authorization = registryAuthenticator.authenticatePull(credentials);
        return new ImageAndAuthorization(
            pullBaseImage(authorization, progressEventDispatcher), authorization);
      }
    }
  }

  /**
   * Pulls the base image.
   *
   * @param registryAuthorization authentication credentials to possibly use
   * @param progressEventDispatcher the {@link ProgressEventDispatcher} for emitting {@link
   *     ProgressEvent}s
   * @return the pulled image
   * @throws IOException when an I/O exception occurs during the pulling
   * @throws RegistryException if communicating with the registry caused a known error
   * @throws LayerCountMismatchException if the manifest and configuration contain conflicting layer
   *     information
   * @throws LayerPropertyNotFoundException if adding image layers fails
   * @throws BadContainerConfigurationFormatException if the container configuration is in a bad
   *     format
   */
  private Image pullBaseImage(
      @Nullable Authorization registryAuthorization,
      ProgressEventDispatcher progressEventDispatcher)
      throws IOException, RegistryException, LayerPropertyNotFoundException,
          LayerCountMismatchException, BadContainerConfigurationFormatException {
    EventHandlers eventHandlers = buildContext.getEventHandlers();
    RegistryClient registryClient =
        buildContext
            .newBaseImageRegistryClientFactory()
            .setAuthorization(registryAuthorization)
            .newRegistryClient();

    ManifestAndDigest<?> manifestAndDigest =
        registryClient.pullManifest(buildContext.getBaseImageConfiguration().getImageTag());
    ManifestTemplate manifestTemplate = manifestAndDigest.getManifest();

    // special handling if we happen upon a manifest list, redirect to a manifest and continue
    // handling it normally
    if (manifestTemplate instanceof V22ManifestListTemplate) {
      eventHandlers.dispatch(
          LogEvent.lifecycle(
              "The base image reference is manifest list, searching for linux/amd64"));
      manifestAndDigest =
          obtainPlatformSpecificImageManifest(
              registryClient, (V22ManifestListTemplate) manifestTemplate);
      manifestTemplate = manifestAndDigest.getManifest();
    }

    switch (manifestTemplate.getSchemaVersion()) {
      case 1:
        V21ManifestTemplate v21ManifestTemplate = (V21ManifestTemplate) manifestTemplate;
        buildContext
            .getBaseImageLayersCache()
            .writeMetadata(
                buildContext.getBaseImageConfiguration().getImage(), v21ManifestTemplate);
        return JsonToImageTranslator.toImage(v21ManifestTemplate);

      case 2:
        eventHandlers.dispatch(
            LogEvent.lifecycle("Using base image with digest: " + manifestAndDigest.getDigest()));
        BuildableManifestTemplate buildableManifestTemplate =
            (BuildableManifestTemplate) manifestTemplate;
        if (buildableManifestTemplate.getContainerConfiguration() == null
            || buildableManifestTemplate.getContainerConfiguration().getDigest() == null) {
          throw new UnknownManifestFormatException(
              "Invalid container configuration in Docker V2.2/OCI manifest: \n"
                  + JsonTemplateMapper.toUtf8String(buildableManifestTemplate));
        }

        DescriptorDigest containerConfigurationDigest =
            buildableManifestTemplate.getContainerConfiguration().getDigest();

        try (ThrottledProgressEventDispatcherWrapper progressEventDispatcherWrapper =
            new ThrottledProgressEventDispatcherWrapper(
                progressEventDispatcher.newChildProducer(),
                "pull container configuration " + containerConfigurationDigest)) {
          String containerConfigurationString =
              Blobs.writeToString(
                  registryClient.pullBlob(
                      containerConfigurationDigest,
                      progressEventDispatcherWrapper::setProgressTarget,
                      progressEventDispatcherWrapper::dispatchProgress));

          ContainerConfigurationTemplate containerConfigurationTemplate =
              JsonTemplateMapper.readJson(
                  containerConfigurationString, ContainerConfigurationTemplate.class);
          buildContext
              .getBaseImageLayersCache()
              .writeMetadata(
                  buildContext.getBaseImageConfiguration().getImage(),
                  buildableManifestTemplate,
                  containerConfigurationTemplate);
          return JsonToImageTranslator.toImage(
              buildableManifestTemplate, containerConfigurationTemplate);
        }
    }

    throw new IllegalStateException("Unknown manifest schema version");
  }

  /**
   * Looks through a manifest list for any amd64/linux manifest and downloads and returns the first
   * manifest it finds.
   */
  private ManifestAndDigest<?> obtainPlatformSpecificImageManifest(
      RegistryClient registryClient, V22ManifestListTemplate manifestListTemplate)
      throws RegistryException, IOException {

    List<String> digests = manifestListTemplate.getDigestsForPlatform("amd64", "linux");
    if (digests.size() == 0) {
      String errorMessage =
          "Unable to find amd64/linux manifest in manifest list at: "
              + buildContext.getBaseImageConfiguration().getImage();
      buildContext.getEventHandlers().dispatch(LogEvent.error(errorMessage));
      throw new RegistryException(errorMessage);
    }
    return registryClient.pullManifest(digests.get(0));
  }

  /**
   * Retrieves the cached base image.
   *
   * @return the cached image, if found
   * @throws IOException when an I/O exception occurs
   * @throws CacheCorruptedException if the cache is corrupted
   * @throws LayerPropertyNotFoundException if adding image layers fails
   * @throws BadContainerConfigurationFormatException if the container configuration is in a bad
   *     format
   */
  private Optional<Image> getCachedBaseImage()
      throws IOException, CacheCorruptedException, BadContainerConfigurationFormatException,
          LayerCountMismatchException {
    ImageReference baseImage = buildContext.getBaseImageConfiguration().getImage();
    Optional<ManifestAndConfig> metadata =
        buildContext.getBaseImageLayersCache().retrieveMetadata(baseImage);
    if (!metadata.isPresent()) {
      return Optional.empty();
    }

    ManifestTemplate manifestTemplate = metadata.get().getManifest();
    if (manifestTemplate instanceof V21ManifestTemplate) {
      return Optional.of(JsonToImageTranslator.toImage((V21ManifestTemplate) manifestTemplate));
    }

    ContainerConfigurationTemplate configurationTemplate =
        metadata.get().getConfig().orElseThrow(IllegalStateException::new);
    return Optional.of(
        JsonToImageTranslator.toImage(
            (BuildableManifestTemplate) manifestTemplate, configurationTemplate));
  }

  private Optional<RegistryAuthenticator> getRegistryAuthenticator(
      HttpResponseException httpResponseException) throws RegistryException, IOException {
    RegistryClient registryClient =
        buildContext.newBaseImageRegistryClientFactory().newRegistryClient();
    String wwwAuthenticate = httpResponseException.getHeaders().getAuthenticate();
    if (wwwAuthenticate != null) {
      // The registry requires us to authenticate using the Docker Token Authentication.
      // See https://docs.docker.com/registry/spec/auth/token
      return registryClient.getRegistryAuthenticator(wwwAuthenticate);
    } else {
      // It's unexpected to reach here; the registry should have returned WWW-Authenticate on
      // the first manifest request. However, for one last attempt, let's try <server url>/v2/
      // and hope the registry will return the header.
      return registryClient.getRegistryAuthenticator();
    }
  }
}
