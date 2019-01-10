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
	void otherPathThatShouldBeSkipped()
	{
		assertTrue(Main.shouldSkipPath(new File(".git").toPath()));
		assertTrue(Main.shouldSkipPath(new File(".git/objects/df/c680b62abdf39c3a47d6a4a5169eb9a70a6b66").toPath()));
	}

	@Test
	void pathThatShouldNotBeSkippedBecauseSpecialCase()
	{
		assertFalse(Main.shouldSkipPath(new File("question/type/stack/thirdparty/php-peg/lib/vendor").toPath()));
		assertFalse(Main.shouldSkipPath(new File("question/type/stack/thirdparty/php-peg/lib/vendor/frogs/whatever").toPath()));
		assertFalse(Main.shouldSkipPath(new File("question\\type\\stack\\thirdparty\\php-peg\\lib\\vendor\\silly").toPath()));
	}
}
