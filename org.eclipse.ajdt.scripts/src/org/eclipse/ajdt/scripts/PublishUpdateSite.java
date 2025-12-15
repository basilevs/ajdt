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

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.ProcessBuilder.Redirect;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
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
	private static final String TARGET_DIRECTORY_PARENT_TEMPLATE = "%s/%s";
	private static final String ARTIFACTS_XML_TEMPLATE, CONTENT_XML_TEMPLATE;
	static {
		ARTIFACTS_XML_TEMPLATE = readTextFile("org.eclipse.ajdt.releng/dev-compositeArtifacts.xml");
		CONTENT_XML_TEMPLATE   = readTextFile("org.eclipse.ajdt.releng/dev-compositeContent.xml");
	}
	private final Mode mode;
	private final boolean dryRun;
	private final String targetPrefix;
	private final String connection;
	
	private static class CodeException extends Exception {
		private static final long serialVersionUID = -114425129892851356L;
		private final int code;
		public CodeException(int code) {
			this.code = code;
		}
	}

	private enum Mode {
		NIGHTLY {
			@Override
			void before(PublishUpdateSite publishUpdateSite) {
				String version = publishUpdateSite.getVersion();
				if (publishUpdateSite.existsRemotely(RELEASE.targetDirectory(version))) {
					throw new IllegalStateException(version + " is already released");
				}
				String target = targetDirectory(version);
				publishUpdateSite.deleteRemotely(target);
			}

			@Override
			String targetDirectory(String version) {
				return name().toLowerCase() + "/" + getMinor(version);
			}

			@Override
			void after(PublishUpdateSite publishUpdateSite) {
				// NOTHING. Only used by RELEASE
			}
		},
		RELEASE {
			@Override
			void before(PublishUpdateSite publishUpdateSite) {
				String target = targetDirectory(publishUpdateSite.getVersion());
				if (publishUpdateSite.existsRemotely(target)) {
					throw new IllegalStateException(target + " already exists");
				}
			}

			@Override
			String targetDirectory(String version) {
				return name().toLowerCase() + "/" + version;
			}

			@Override
			void after(PublishUpdateSite publishUpdateSite) throws CodeException {
				String version = publishUpdateSite.getVersion();
				String compositeDir = name().toLowerCase() + "/" + getMinor(version);
				publishUpdateSite.writeComposite(compositeDir, "../"+publishUpdateSite.getVersion());
			}
			
		};

		abstract void before(PublishUpdateSite publishUpdateSite);
		abstract void after(PublishUpdateSite publishUpdateSite) throws CodeException;
		abstract String targetDirectory(String version);
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
					+ "java org.eclipse.ajdt.scripts/src/org/eclipse/ajdt/scripts/PublishUpdateSite.java [--dry-run] <user>@projects-storage.eclipse.org nightly /home/data/httpd/download.eclipse.org/<target_prefix>\n"
					+ "java org.eclipse.ajdt.scripts/src/org/eclipse/ajdt/scripts/PublishUpdateSite.java [--dry-run] <user>@projects-storage.eclipse.org release /home/data/httpd/download.eclipse.org/<target_prefix>\n"
					+ "<target_prefix> - rcptt/dependencies/ajdt or ajdt. \n"
					+ "Example output directory: genie.rcptt@projects-storage.eclipse.org:" + TARGET_DIRECTORY_PARENT_TEMPLATE.formatted("/home/data/httpd/download.eclipse.org/rcptt/dependencies/ajdt", Mode.RELEASE.name().toLowerCase()) + "/2.2.4");
			System.exit(1);
		}
		String connection = arguments.remove(0);
		Mode mode = Mode.valueOf(arguments.remove(0).toUpperCase());
		String targetPrefix = arguments.remove(0);
		try {
			new PublishUpdateSite(connection, mode, targetPrefix, dryRun).publish();
		} catch (CodeException e) {
			System.exit(e.code);
		}
	}

	public void publish() throws CodeException {
		Path origin = Path.of("org.eclipse.ajdt.releng/target/repository");
		Path artifactsPath = origin.resolve("artifacts.jar");
		if (!Files.isRegularFile(artifactsPath)) {
			throw new IllegalStateException(artifactsPath + " does not exist");
		}
		failNonZero(executeRemotely(asList("echo", "Connection succesfull")));
		mode.before(this);
		failNonZero(dryExecuteRemotely(asList("mkdir", "-p", targetDirectoryParent())));
		failNonZero(dryExecute(dryRun, asList("scp", "-r", origin.toString(), connection + ":" + targetPrefix + "/" + mode.targetDirectory(getVersion()))));
		mode.after(this);
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

	private static String readTextFile(String path) {
		Path pathObj = Path.of(path);
		try {
			return Files.readString(pathObj, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new IllegalStateException("Can't read " + pathObj.toAbsolutePath(), e);
		}
	}

	private void writeComposite(String compositeDir, String relativePath) throws CodeException {
		String compositeArtifacts = massageTemplate(ARTIFACTS_XML_TEMPLATE, relativePath);
		String compositeContent   = massageTemplate(CONTENT_XML_TEMPLATE, relativePath);
		failNonZero(writeRemote(compositeDir, "compositeArtifacts.xml", compositeArtifacts));
		failNonZero(writeRemote(compositeDir, "compositeContent.xml", compositeContent));
	}

	private String massageTemplate(String template, String relativePath) {
		String result = template;
		result = result.replaceAll("%TIMESTAMP%", "" + Instant.now().toEpochMilli());
		result = result.replaceAll("%BUILD-ID%", relativePath);
		return result;
	}


	private int writeRemote(String directory, String filename, String content) {
		directory = targetPrefix + "/" + directory;
		dryExecuteRemotely(asList("mkdir", "-p", directory));
		List<String> sshCommand = sshCommand(asList("tee", directory + "/" + filename));
		if (dryRun) {
			System.out.println("Writing:\n" + content);
			System.out.println("Skipping: " + sshCommand);
			return 0;
		}
		ProcessBuilder builder = new ProcessBuilder(sshCommand).redirectError(Redirect.INHERIT).redirectOutput(Redirect.INHERIT);
		try {
			Process process = builder.start();
			try (BufferedWriter outputWriter = process.outputWriter(StandardCharsets.UTF_8)) {
				outputWriter.append(content);
			}
			int exitCode = process.waitFor();
			System.out.println("Executed: '" + builder.command() + "'. Exit code: " + exitCode);
			return exitCode;
		} catch (InterruptedException | IOException e) {
			throw new AssertionError(e);
		}
	}


	private static void failNonZero(int exitCode) throws CodeException {
		if (exitCode != 0) {
			throw new CodeException(exitCode);
		}
	}

	private int executeRemotely(List<String> command) {
		checkForSuspiciousSymbols(command);
		return dryExecute(false, sshCommand(command));
	}
	
	private void deleteRemotely(String path) {
		List<String> command = asList("rm", "-rf", targetPrefix + "/" + path);
		dryExecuteRemotely(command);
	}
	
	private boolean existsRemotely(String path) {
		return executeRemotely(asList("test", "-e", targetPrefix + "/" + path)) == 0;
	}

	
	private int dryExecuteRemotely(List<String> command) {
		checkForSuspiciousSymbols(command);
		return dryExecute(dryRun, sshCommand(command));
	}


	private List<String> sshCommand(List<String> command) {
		return concat(asList("ssh", "-oBatchMode=yes", connection), command);
	}

	private void checkForSuspiciousSymbols(List<String> command) {
		if (command.stream().anyMatch(s -> s.contains("$"))) {
			throw new IllegalArgumentException(
					"Suspicious symbol $ in the command: " + command.stream().collect(Collectors.joining(" ")));
		}
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

	private String targetDirectoryParent() {
		return TARGET_DIRECTORY_PARENT_TEMPLATE.formatted(targetPrefix, mode.name().toLowerCase());
	}
	
	private String getVersion() {
		String result = extractMavenVersion(Path.of("pom.xml")).replace("-SNAPSHOT", "");
		System.out.println("Current version: " + result);
		return result;
	}

	private static String getMinor(String version) {
		String[] segments = version.split("\\.", 3);
		return ""+segments[0]+"." + segments[1];
	}

}
