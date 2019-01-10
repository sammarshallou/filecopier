package uk.ac.open.lts.filecopier;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;

import org.junit.jupiter.api.Test;

public class MainTest
{
	@Test
	void pathThatShouldNotBeSkipped()
	{
		assertFalse(Main.shouldSkipPath(new File("moodle/lib").toPath()));
	}

	@Test
	void pathThatShouldNotBeSkippedWindowsDelimiters()
	{
		assertFalse(Main.shouldSkipPath(new File("moodle\\lib").toPath()));
	}

	@Test
	void pathThatShouldBeSkipped()
	{
		assertTrue(Main.shouldSkipPath(new File("moodle/vendor").toPath()));
	}

	@Test
	void pathThatShouldNotBeSkippedBecauaseSpecialCase()
	{
		assertFalse(Main.shouldSkipPath(new File("moodle/question/type/stack/question/type/stack/thirdparty/php-peg/lib/vendor").toPath()));
	}
}
