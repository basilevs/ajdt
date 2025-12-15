/*******************************************************************************
 * Copyright (c) 2025 Xored Software Inc and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors:
 *     Xored Software Inc - initial API and implementation and/or initial documentation
 *******************************************************************************/
package org.eclipse.ajdt.scripts;

import static java.util.Arrays.asList;
import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Node;

/**
 * Exports org.eclipse.ajdt.releng/target/repositoryorg.eclipse.ajdt.releng/target/repository to download.eclipse.org
 */
public class PublishUpdateSite {
	private static final String TARGET_DIRECTORY_PARENT_TEMPLATE = "/home/data/httpd/download.eclipse.org/%s/%s";
	private final Mode mode;
	private final boolean dryRun;
	private final String targetPrefix;
	private final String connection;

	private enum Mode {
		NIGHTLY {
			@Override
			void before(PublishUpdateSite publishUpdateSite) {
				publishUpdateSite.deleteTargetDirectory();
			}
		},
		RELEASE {
			@Override
			void before(PublishUpdateSite publishUpdateSite) {
				publishUpdateSite.ensureTargetDirectoryDoesNotAlreadyExist();
			}

		};

		abstract void before(PublishUpdateSite publishUpdateSite);
	}

	public PublishUpdateSite(String connection, Mode mode, String targetPrefix, boolean dryRun) {
		this.mode = requireNonNull(mode);
		this.dryRun = dryRun;
		this.targetPrefix = requireNonNull(targetPrefix);
		this.connection = requireNonNull(connection);
		if (connection.contains(" ")) {
			throw new IllegalStateException("Spaces in connection string are not supported: " + connection);
		}
		if (connection.contains(":")) {
			throw new IllegalStateException("Colons in connection string are not supported: " + connection);
		}
	}

	public static void main(String[] args) {
		ArrayList<String> arguments = new ArrayList<String>(asList(args));
		boolean dryRun = arguments.removeIf(Predicate.isEqual("--dry-run"));
		if (arguments.contains("--help") || arguments.size() != 3) {
			System.err.println("Usage:\n"
					+ "java org.eclipse.ajdt.scripts/src/org/eclipse/ajdt/scripts/PublishUpdateSite.java [--dry-run] <user>@projects-storage.eclipse.org nightly <target_prefix>\n"
					+ "java org.eclipse.ajdt.scripts/src/org/eclipse/ajdt/scripts/PublishUpdateSite.java [--dry-run] <user>@projects-storage.eclipse.org release <target_prefix>\n"
					+ "<target_prefix> - rcptt/dependencies/ajdt or ajdt. \n"
					+ "Example output directory: genie.rcptt@projects-storage.eclipse.org:" + TARGET_DIRECTORY_PARENT_TEMPLATE.formatted("rcptt/dependencies/ajdt", Mode.RELEASE.name().toLowerCase()) + "/2.2.4");
			System.exit(1);
		}
		String connection = arguments.remove(0);
		Mode mode = Mode.valueOf(arguments.remove(0).toUpperCase());
		String targetPrefix = arguments.remove(0);
		new PublishUpdateSite(connection, mode, targetPrefix, dryRun).publish();
	}

	public void publish() {
		Path origin = Path.of("org.eclipse.ajdt.releng/target/repository");
		Path artifactsPath = origin.resolve("artifacts.jar");
		if (!Files.isRegularFile(artifactsPath)) {
			throw new IllegalStateException(artifactsPath + " does not exist");
		}
		failNonZero(executeRemotely(false, asList("echo", "Connection succesfull")));
		mode.before(this);
		failNonZero(executeRemotely(dryRun, asList("mkdir", "-p", targetDirectoryParent())));
		failNonZero(dryExecute(dryRun, asList("scp", "-r", origin.toString(), connection + ":" + targetDirectory())));
	}

	private void failNonZero(int exitCode) {
		if (exitCode != 0) {
			System.exit(exitCode);
		}
	}

	private int executeRemotely(boolean dryRun, List<String> command) {
		if (command.stream().anyMatch(s -> s.contains("$"))) {
			throw new IllegalArgumentException(
					"Suspicious symbol $ in the command: " + command.stream().collect(Collectors.joining(" ")));
		}

		return dryExecute(dryRun, concat(asList("ssh", "-oBatchMode=yes", connection), command));
	}

	private int dryExecute(boolean dryRun, List<String> command2) {
		if (dryRun) {
			System.out.println("Skipping execution of: " + command2);
			return 0;
		} else {
			System.out.println("Executing: " + command2);
			return execute(command2);
		}
	}

	private static int execute(List<String> command) {
		ProcessBuilder process = new ProcessBuilder(command).inheritIO();
		try {
			int exitCode = process.start().waitFor();
			System.out.println("Executed: '" + process.command() + "'. Exit code: " + exitCode);
			return exitCode;
		} catch (InterruptedException | IOException e) {
			throw new AssertionError(e);
		}
	}

	private <T> List<T> concat(List<T> first, List<T> second) {
		return Stream.concat(first.stream(), second.stream()).toList();
	}

	private void ensureTargetDirectoryDoesNotAlreadyExist() {
		String target = targetDirectory();
		failNonZero(executeRemotely(false, asList("test", "!", "-e", target)));
	}

	private void deleteTargetDirectory() {
		String target = targetDirectory();
		assert target.length() > 10;
		List<String> command = asList("rm", "-rf", targetDirectory());
		executeRemotely(dryRun, command);
	}

	private String targetDirectoryParent() {
		return TARGET_DIRECTORY_PARENT_TEMPLATE.formatted(targetPrefix, mode.name().toLowerCase());
	}
	
	private String targetDirectory() {
		return targetDirectoryParent() + "/" + getVersion();
	}

	private String getVersion() {
		return extractMavenVersion(Path.of("pom.xml")).replace("-SNAPSHOT", "");
	}

	public static String extractMavenVersion(Path pomPath) {
		try {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setNamespaceAware(false); 
			factory.setExpandEntityReferences(false);

			DocumentBuilder builder = factory.newDocumentBuilder();
			Document document = builder.parse(pomPath.toFile());
			XPath xpath = XPathFactory.newInstance().newXPath();

			Node versionNode = (Node) xpath.evaluate("/project/version", document, XPathConstants.NODE);
			if (versionNode != null && !versionNode.getTextContent().isBlank()) {
				return versionNode.getTextContent().trim();
			}

			Node parentVersionNode = (Node) xpath.evaluate("/project/parent/version", document, XPathConstants.NODE);
			if (parentVersionNode != null && !parentVersionNode.getTextContent().isBlank()) {
				return parentVersionNode.getTextContent().trim();
			}

			throw new IllegalStateException("No <version> found in " + pomPath.toAbsolutePath());

		} catch (Exception e) {
			throw new RuntimeException("Failed to extract version from " + pomPath.toAbsolutePath(), e);
		}
	}

}
