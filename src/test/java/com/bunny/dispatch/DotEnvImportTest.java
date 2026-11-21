package com.bunny.dispatch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Probes the {@code .env} story that README and SETUP will tell: the shipped
 * {@code application.yaml} declares {@code optional:file:.env[.properties]}, that import really
 * reads a {@code .env} in the working directory as properties, {@code optional:} is load-bearing
 * (a fresh clone with no {@code .env} still boots) and a system property outranks the file, so a
 * deployment environment always wins over a stale local file. No database, no container — the
 * claim under test is config loading, not the application.
 */
class DotEnvImportTest {

	private static final String KEY = "dispatch.dotenv.probe";

	@TempDir
	Path tempDir;

	@Test
	void shippedApplicationYamlImportsLocalDotenv() throws IOException {
		assertThat(readClasspath("application.yaml")).contains("optional:file:.env[.properties]");
	}

	@Test
	void shippedConfigReadsDotenvFromWorkingDirectory() throws IOException {
		Path env = Path.of(".env");
		// never touch a developer's real credentials file — only probe when none exists
		assumeTrue(Files.notExists(env), "a local .env already exists; leaving it alone");
		Files.writeString(env, KEY + "=from-working-dir\n", StandardCharsets.UTF_8);
		try {
			plainRunner().run(context -> assertThat(context.getEnvironment().getProperty(KEY))
					.as("the shipped spring.config.import must read a ./.env")
					.isEqualTo("from-working-dir"));
		}
		finally {
			Files.deleteIfExists(env);
		}
	}

	@Test
	void dotenvFileIsParsedAsProperties() throws IOException {
		importDotenv(true);
		writeDotenv("DB_USERNAME=dispatch_user\n# a comment line\n" + KEY + "=from-dotenv\n");
		runnerWithTempConfig().run(context ->
				assertThat(context.getEnvironment().getProperty(KEY)).isEqualTo("from-dotenv"));
	}

	@Test
	void optionalImportToleratesAbsentDotenv() {
		importDotenv(true);
		runnerWithTempConfig().run(context -> assertThat(context).hasNotFailed());
	}

	@Test
	void nonOptionalImportFailsOnAbsentDotenv() {
		importDotenv(false);
		runnerWithTempConfig().run(context -> assertThat(context).hasFailed());
	}

	@Test
	void systemPropertyOutranksDotenvFile() throws IOException {
		importDotenv(true);
		writeDotenv(KEY + "=from-dotenv\n");
		System.setProperty(KEY, "from-system");
		try {
			runnerWithTempConfig().run(context ->
					assertThat(context.getEnvironment().getProperty(KEY))
							.as("a deployment-time -D must beat a stale local .env")
							.isEqualTo("from-system"));
		}
		finally {
			System.clearProperty(KEY);
		}
	}

	private void importDotenv(boolean optional) {
		String prefix = optional ? "optional:" : "";
		writeConfigFile("spring.config.import=" + prefix + "file:"
				+ absolute(tempDir.resolve(".env")) + "[.properties]\n");
	}

	private ApplicationContextRunner plainRunner() {
		return new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer());
	}

	private ApplicationContextRunner runnerWithTempConfig() {
		return plainRunner().withPropertyValues(
				"spring.config.location=file:" + absolute(tempDir.resolve("application.properties")));
	}

	private void writeDotenv(String content) throws IOException {
		Files.writeString(tempDir.resolve(".env"), content, StandardCharsets.UTF_8);
	}

	private void writeConfigFile(String content) {
		try {
			Files.writeString(tempDir.resolve("application.properties"), content, StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private String absolute(Path path) {
		return path.toAbsolutePath().toString().replace('\\', '/');
	}

	private String readClasspath(String name) throws IOException {
		try (InputStream in = new ClassPathResource(name).getInputStream()) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

}
