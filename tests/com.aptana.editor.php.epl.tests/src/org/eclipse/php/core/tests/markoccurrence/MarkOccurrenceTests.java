/*******************************************************************************
 * Copyright (c) 2009 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *     Zend Technologies
 *******************************************************************************/
package org.eclipse.php.core.tests.markoccurrence;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import junit.extensions.TestSetup;
import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.php.core.tests.AbstractPDTTTest;
import org.eclipse.php.core.tests.PdttFile;
import org.eclipse.php.core.tests.TestUtils;
import org2.eclipse.php.internal.core.PHPVersion;
import org2.eclipse.php.internal.core.ast.locator.PhpElementConciliator;
import org2.eclipse.php.internal.core.ast.nodes.ASTNode;
import org2.eclipse.php.internal.core.ast.nodes.ASTParser;
import org2.eclipse.php.internal.core.ast.nodes.Identifier;
import org2.eclipse.php.internal.core.ast.nodes.Program;
import org2.eclipse.php.internal.core.corext.NodeFinder;
import org2.eclipse.php.internal.core.search.IOccurrencesFinder;
import org2.eclipse.php.internal.core.search.IOccurrencesFinder.OccurrenceLocation;
import org2.eclipse.php.internal.core.search.OccurrencesFinderFactory;

import com.aptana.editor.php.core.PHPNature;
import com.aptana.editor.php.core.PHPVersionProvider;
import com.aptana.editor.php.core.model.ISourceModule;
import com.aptana.editor.php.epl.tests.Activator;
import com.aptana.editor.php.internal.model.utils.ModelUtils;
import com.aptana.editor.php.internal.typebinding.TypeBindingBuilder;

@SuppressWarnings("nls")
public class MarkOccurrenceTests extends AbstractPDTTTest
{

	protected static final char OFFSET_CHAR = '|';
	protected static final Map<PHPVersion, String[]> TESTS = new LinkedHashMap<PHPVersion, String[]>();
	protected static int testNumber;
	static
	{
		TESTS.put(PHPVersion.PHP5, new String[] { "/workspace/markoccurrence/php5" });
		TESTS.put(PHPVersion.PHP5_3,
				new String[] { "/workspace/markoccurrence/php5", "/workspace/markoccurrence/php53" });
	};

	protected static IProject project;
	protected static IFile testFile;

	public static void setUpSuite() throws Exception
	{
		IWorkspace workspace = ResourcesPlugin.getWorkspace();
		project = workspace.getRoot().getProject("MarkOccurrenceTests");
		if (project.exists())
		{
			return;
		}

		// configure nature
		IProjectDescription projectDescription = workspace.newProjectDescription("MarkOccurrenceTests");
		projectDescription.setNatureIds(new String[] { PHPNature.NATURE_ID });
		project.create(projectDescription, null);
		project.open(null);
	}

	public static void tearDownSuite() throws Exception
	{
		project.close(null);
		project.delete(true, true, null);
		project = null;
	}

	public MarkOccurrenceTests(String description)
	{
		super(description);
	}

	public static Test suite()
	{

		TestSuite suite = new TestSuite("Auto Mark Occurrence Tests");

		for (final PHPVersion phpVersion : TESTS.keySet())
		{
			TestSuite phpVerSuite = new TestSuite(phpVersion.getAlias());

			for (String testsDirectory : TESTS.get(phpVersion))
			{

				for (final String fileName : getPDTTFiles(testsDirectory))
				{
					try
					{
						final PdttFile pdttFile = new PdttFile(fileName);
						phpVerSuite.addTest(new MarkOccurrenceTests(phpVersion.getAlias() + " - /" + fileName)
						{

							protected void setUp() throws Exception
							{
								TestUtils.setProjectPhpVersion(project, phpVersion);
								pdttFile.applyPreferences();
							}

							protected void tearDown() throws Exception
							{
								if (testFile != null)
								{
									safeDelete(testFile);
									testFile = null;
								}
							}

							protected void runTest() throws Throwable
							{
								// OccurrenceLocation[] proposals =
								// getProposals(pdttFile
								// .getFile());
								compareProposals(pdttFile.getFile());
							}
						});
					}
					catch (final Exception e)
					{
						phpVerSuite.addTest(new TestCase(fileName)
						{
							// dummy test indicating PDTT file parsing failure
							protected void runTest() throws Throwable
							{
								throw e;
							}
						});
					}
				}
			}
			suite.addTest(phpVerSuite);
		}

		// Create a setup wrapper
		TestSetup setup = new TestSetup(suite)
		{
			protected void setUp() throws Exception
			{
				setUpSuite();
			}

			protected void tearDown() throws Exception
			{
				tearDownSuite();
			}
		};
		return setup;
	}

	/**
	 * Creates test file with the specified content and calculates the offset at OFFSET_CHAR. Offset character itself is
	 * stripped off.
	 * 
	 * @param data
	 *            File data
	 * @return offset where's the offset character set.
	 * @throws Exception
	 */
	protected static void compareProposals(String data) throws Exception
	{

		int offset = data.lastIndexOf(OFFSET_CHAR);
		if (offset == -1)
		{
			throw new IllegalArgumentException("Offset character is not set"); //$NON-NLS-1$
		}
		List<Integer> starts = new ArrayList<Integer>();
		int startIndex = -1;
		while ((startIndex = data.indexOf('%', startIndex + 1)) >= 0)
		{
			starts.add(startIndex);
		}
		if (starts.size() % 2 != 0)
		{
			throw new IllegalArgumentException("% must be paired"); //$NON-NLS-1$
		}
		List<Integer> newStarts = new ArrayList<Integer>();
		for (int i = 0; i < starts.size(); i++)
		{
			int oldstart = starts.get(i) - i;
			if (oldstart > offset)
			{
				oldstart--;
			}
			newStarts.add(oldstart);
		}
		// replace the offset character
		data = data.replaceAll("%", ""); //$NON-NLS-1$ //$NON-NLS-2$

		offset = data.lastIndexOf(OFFSET_CHAR);
		// replace the offset character
		data = data.substring(0, offset) + data.substring(offset + 1);

		testNumber++;
		testFile = project.getFile("test-" + testNumber + ".php"); //$NON-NLS-1$
		if (testFile.exists())
		{
			safeDelete(testFile);
			testFile = project.getFile("test-" + testNumber + ".php"); //$NON-NLS-1$
		}
		testFile.create(new ByteArrayInputStream(data.getBytes()), true, null);
		project.refreshLocal(IResource.DEPTH_INFINITE, null);
		project.build(IncrementalProjectBuilder.FULL_BUILD, null);

		TestUtils.waitForAutoBuild();
		TestUtils.waitForIndexer();

		Program astRoot = createProgramFromSource(testFile);
		ASTNode selectedNode = NodeFinder.perform(astRoot, offset, 0);
		OccurrenceLocation[] locations = null;
		if (selectedNode != null && (selectedNode instanceof Identifier || (isScalarButNotInString(selectedNode))))
		{
			int type = PhpElementConciliator.concile(selectedNode);
			if (markOccurrencesOfType(type))
			{
				IOccurrencesFinder finder = OccurrencesFinderFactory.getOccurrencesFinder(type);
				if (finder != null)
				{
					if (finder.initialize(astRoot, selectedNode) == null)
					{
						locations = finder.getOccurrences();
					}
				}
			}
		}
		compareProposals(locations, newStarts);
	}

	/**
	 * Returns is the occurrences of the type should be marked.
	 * 
	 * @param type
	 *            One of the {@link PhpElementConciliator} constants integer type.
	 * @return True, if the type occurrences should be marked; False, otherwise.
	 */
	public static boolean markOccurrencesOfType(int type)
	{
		switch (type)
		{
			case PhpElementConciliator.CONCILIATOR_GLOBAL_VARIABLE:
			case PhpElementConciliator.CONCILIATOR_LOCAL_VARIABLE:
			case PhpElementConciliator.CONCILIATOR_FUNCTION:
			case PhpElementConciliator.CONCILIATOR_CLASSNAME:
			case PhpElementConciliator.CONCILIATOR_CONSTANT:
			case PhpElementConciliator.CONCILIATOR_CLASS_MEMBER:
				return true;
			case PhpElementConciliator.CONCILIATOR_UNKNOWN:
			case PhpElementConciliator.CONCILIATOR_PROGRAM:
			default:
				return false;
		}
	}

	/**
	 * Checks whether or not the node is a scalar and return true only if the scalar is not part of a string
	 * 
	 * @param node
	 * @return
	 */
	public static boolean isScalarButNotInString(ASTNode node)
	{
		return (node.getType() == ASTNode.SCALAR) && (node.getParent().getType() != ASTNode.QUOTE);
	}

	public static Program createProgramFromSource(IFile file) throws Exception
	{
		ISourceModule source = ModelUtils.getModule(file);
		Program program = createProgramFromSource(source);
		TypeBindingBuilder.buildBindings(program);
		return program;
	}

	public static Program createProgramFromSource(ISourceModule source) throws Exception
	{
		PHPVersion version;
		// Set this plugin id as the PHPVersionProvider preferences qualifier.
		PHPVersionProvider.getInstance().setPreferencesQualifier(Activator.PLUGIN_ID);
		if (project != null)
		{
			version = PHPVersionProvider.getPHPVersion(project);
		}
		else
		{
			version = PHPVersionProvider.getDefaultPHPVersion();
		}
		ASTParser newParser = ASTParser.newParser(version, (ISourceModule) source);
		Program program = newParser.createAST(null);
		TypeBindingBuilder.buildBindings(program);
		return program;
	}

	protected static ISourceModule getSourceModule()
	{
		return ModelUtils.getModule(testFile);
	}

	@SuppressWarnings("unused")
	public static void compareProposals(OccurrenceLocation[] proposals, List<Integer> starts) throws Exception
	{
		// String[] lines = pdttFile.getExpected().split("\n");
		proposals = olderLocations(proposals);
		boolean proposalsEqual = true;
		if (proposals.length == starts.size() / 2)
		{
			for (int i = 0; i < proposals.length; i++)
			{
				if (!(proposals[i].getOffset() == starts.get(i * 2) && (proposals[i].getOffset() + proposals[i]
						.getLength()) == starts.get(i * 2 + 1)))
				{
					proposalsEqual = false;
					break;
				}
			}
		}
		else if (proposals == null && starts.size() == 0)
		{
			// proposalsEqual = true;
		}
		else
		{
			proposalsEqual = false;
		}

		if (!proposalsEqual)
		{
			StringBuilder errorBuf = new StringBuilder();
			errorBuf.append("\nEXPECTED COMPLETIONS LIST:\n-----------------------------\n");
			for (int i = 0; i < starts.size() / 2; i++)
			{
				errorBuf.append('[').append(starts.get(i * 2)).append(',')
						.append(starts.get(i * 2 + 1) - starts.get(i * 2)).append(']').append("\n");
			}
			errorBuf.append("\nACTUAL COMPLETIONS LIST:\n-----------------------------\n");
			for (OccurrenceLocation p : proposals)
			{
				errorBuf.append('[').append(p.getOffset()).append(',').append(p.getLength()).append(']').append("\n");
			}
			fail(errorBuf.toString());
		}
	}

	private static OccurrenceLocation[] olderLocations(OccurrenceLocation[] proposals)
	{
		if (proposals == null)
		{
			return new OccurrenceLocation[0];
		}
		List<OccurrenceLocation> result = new ArrayList<OccurrenceLocation>();

		for (int i = 0; i < proposals.length; i++)
		{
			result.add(proposals[i]);
		}
		Collections.sort(result, new Comparator<OccurrenceLocation>()
		{

			public int compare(OccurrenceLocation o1, OccurrenceLocation o2)
			{

				return o1.getOffset() - o2.getOffset();
			}
		});
		return result.toArray(new OccurrenceLocation[result.size()]);
	}
}
