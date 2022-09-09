/*
 * Copyright 2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.buildinit.plugins;

import org.apache.commons.io.FileUtils;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.UncheckedIOException;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.internal.tasks.userinput.UserInputHandler;
import org.gradle.api.logging.Logger;
import org.gradle.api.plugins.jvm.internal.JvmEcosystemUtilities;
import org.gradle.api.specs.Spec;
import org.gradle.buildinit.InsecureProtocolOption;
import org.gradle.buildinit.tasks.InitBuild;
import org.gradle.internal.file.RelativeFilePathResolver;

import javax.annotation.Nullable;
import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.Callable;

import static com.google.common.base.Strings.isNullOrEmpty;

/**
 * The build init plugin.
 *
 * @see <a href="https://docs.gradle.org/current/userguide/build_init_plugin.html">Build Init plugin reference</a>
 */
public class BuildInitPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        if (project.getParent() == null) {
            project.getTasks().register("init", InitBuild.class, initBuild -> {
                initBuild.setGroup("Build Setup");
                initBuild.setDescription("Initializes a new Gradle build.");

                ProjectInternal projectInternal = (ProjectInternal) project;
                RelativeFilePathResolver resolver = projectInternal.getFileResolver();
                File buildFile = project.getBuildFile();
                FileDetails buildFileDetails = FileDetails.of(buildFile, resolver);
                File settingsFile = projectInternal.getGradle().getSettings().getSettingsScript().getResource().getLocation().getFile();
                FileDetails settingsFileDetails = FileDetails.of(settingsFile, resolver);
                File projectDir = project.getProjectDir();
                FileDetails projectDirFileDetails = FileDetails.of(projectDir, resolver);

                String skippedMsg = reasonToSkip(projectDirFileDetails, buildFileDetails, settingsFileDetails);
                boolean allowFileOverwrite = false;
                if (!isNullOrEmpty(skippedMsg)) {
                    UserInputHandler inputHandler = ((ProjectInternal) project).getServices().get(UserInputHandler.class);
                    allowFileOverwrite = inputHandler.askYesNoQuestion(skippedMsg +
                        "Allow any existing files in the current directory to be potentially overwritten?", false);
                }

                initBuild.onlyIf(
                    "There is permission to overwrite any existing files in the project directory",
                    new InitBuildOnlyIfSpec(allowFileOverwrite, skippedMsg, initBuild.getLogger())
                );
                initBuild.dependsOn(new InitBuildDependsOnCallable(allowFileOverwrite, skippedMsg));

                ProjectInternal.DetachedResolver detachedResolver = projectInternal.newDetachedResolver();
                initBuild.getProjectLayoutRegistry().getBuildConverter().configureClasspath(
                    detachedResolver, project.getObjects(), projectInternal.getServices().get(JvmEcosystemUtilities.class));

                initBuild.getInsecureProtocol().convention(InsecureProtocolOption.WARN);
            });
        }
    }

    private static class InitBuildOnlyIfSpec implements Spec<Task> {

        private final boolean allowFileOverwrite;
        private final String skippedMsg;
        private final Logger logger;

        private InitBuildOnlyIfSpec(boolean allowFileOverwrite, String skippedMsg, Logger logger) {
            this.allowFileOverwrite = allowFileOverwrite;
            this.skippedMsg = skippedMsg;
            this.logger = logger;
        }

        @Override
        public boolean isSatisfiedBy(Task element) {
            if (!allowFileOverwrite && skippedMsg != null) {
                logger.warn(skippedMsg);
                return false;
            }
            return true;
        }
    }

    private static class InitBuildDependsOnCallable implements Callable<String> {

        private final boolean allowFileOverwrite;

        private final String skippedMsg;

        private InitBuildDependsOnCallable(boolean allowFileOverwrite, String skippedMsg) {
            this.allowFileOverwrite = allowFileOverwrite;
            this.skippedMsg = skippedMsg;
        }

        @Override
        public String call() {
            if (allowFileOverwrite || skippedMsg == null) {
                return "wrapper";
            } else {
                return null;
            }
        }
    }

    private static String reasonToSkip(FileDetails projectDir, FileDetails buildFile, FileDetails settingsFile) {
        try {
            if (FileUtils.isEmptyDirectory(projectDir.file)) {
                return null;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        if (buildFile != null && buildFile.file.exists()) {
            return "The build file '" + buildFile.pathForDisplay + "' already exists. ";
        } else if (settingsFile != null && settingsFile.file.exists()) {
            return "The settings file '" + settingsFile.pathForDisplay + "' already exists. ";
        } else {
            return "The current directory is not empty. ";
        }
    }

    private static class FileDetails {
        final File file;
        final String pathForDisplay;

        public FileDetails(File file, String pathForDisplay) {
            this.file = file;
            this.pathForDisplay = pathForDisplay;
        }

        @Nullable
        public static FileDetails of(@Nullable File file, RelativeFilePathResolver resolver) {
            if (file == null) {
                return null;
            }
            return new FileDetails(file, resolver.resolveForDisplay(file));
        }
    }
}
