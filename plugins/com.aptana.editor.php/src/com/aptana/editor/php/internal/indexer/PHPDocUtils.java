// $codepro.audit.disable platformSpecificLineSeparator
package com.aptana.editor.php.internal.indexer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jface.text.IDocument;
import org2.eclipse.php.internal.core.PHPVersion;
import org2.eclipse.php.internal.core.ast.nodes.ASTParser;
import org2.eclipse.php.internal.core.ast.nodes.Comment;
import org2.eclipse.php.internal.core.ast.nodes.Program;
import org2.eclipse.php.internal.core.compiler.ast.nodes.PHPDocBlock;
import org2.eclipse.php.internal.core.compiler.ast.nodes.PHPDocTag;
import org2.eclipse.php.internal.core.compiler.ast.nodes.VarComment;
import org2.eclipse.php.internal.core.documentModel.phpElementData.IPHPDoc;
import org2.eclipse.php.internal.core.documentModel.phpElementData.IPHPDocBlock;
import org2.eclipse.php.internal.core.documentModel.phpElementData.IPHPDocTag;

import com.aptana.core.logging.IdeLog;
import com.aptana.editor.php.PHPEditorPlugin;
import com.aptana.editor.php.core.PHPVersionProvider;
import com.aptana.editor.php.indexer.IElementEntry;
import com.aptana.editor.php.internal.contentAssist.ContentAssistUtils;
import com.aptana.editor.php.internal.core.builder.IModule;
import com.aptana.editor.php.internal.indexer.language.PHPBuiltins;
import com.aptana.editor.php.internal.parser.phpdoc.FunctionDocumentation;
import com.aptana.editor.php.internal.parser.phpdoc.TypedDescription;
import com.aptana.editor.php.util.EncodingUtils;

/**
 * PHPDoc utilities.
 * 
 * @author Denis Denisenko
 */
public final class PHPDocUtils
{
	private static final String OPEN_BRACKET = "{"; //$NON-NLS-1$
	private static final String CLOSE_BRACKET = "}"; //$NON-NLS-1$
	private static final String DOLLAR = "$"; //$NON-NLS-1$
	private static final String EMPTY_STRING = ""; //$NON-NLS-1$
	private static final Pattern INPUT_TAG_PATTERN = Pattern.compile("<input[^>]*/>|<input\\s*>"); //$NON-NLS-1$

	public static PHPDocBlock findFunctionPHPDocComment(IElementEntry entry, IDocument document, int offset)
	{
		if (entry.getModule() != null)
		{
			return findFunctionPHPDocComment(entry.getModule(), document, offset);
		}
		// In case that the entry module is null, it's probably a PHP API documentation item, so
		// parse the right item.
		try
		{
			String entryPath = entry.getEntryPath();
			if (entryPath != null)
			{
				InputStream stream = PHPBuiltins.getInstance().getBuiltinResourceStream(entryPath);
				if (stream != null)
				{
					BufferedReader reader = new BufferedReader(new InputStreamReader(stream)); // $codepro.audit.disable
																								// closeWhereCreated
					return innerParsePHPDoc(offset, reader);
				}
			}
		}
		catch (Exception ex)
		{
			IdeLog.logError(PHPEditorPlugin.getDefault(), "Failed locating the PHP function doc", ex); //$NON-NLS-1$
			return null;
		}
		return null;
	}

	/**
	 * Finds a PHPDoc comment above the offset in the module specified.
	 * 
	 * @param module
	 *            - module.
	 * @param offset
	 *            - offset.
	 * @return comment contents or null if not found.
	 */
	public static PHPDocBlock findFunctionPHPDocComment(IModule module, IDocument document, int offset)
	{
		try
		{
			Reader innerReader;
			if (document != null)
			{
				innerReader = new StringReader(document.get());// $codepro.audit.disable closeWhereCreated
			}
			else
			{
				innerReader = new InputStreamReader(module.getContents(), EncodingUtils.getModuleEncoding(module));// $codepro.audit.disable
																													// closeWhereCreated
			}
			BufferedReader reader = new BufferedReader(innerReader);
			return innerParsePHPDoc(offset, reader);
		}
		catch (Exception ex)
		{
			return null;
		}
	}

	/**
	 * Finds a PHPDoc comment above the offset in the source that is read from the given BufferedReader.
	 * 
	 * @param offset
	 * @param reader
	 * @return
	 * @throws IOException
	 * @throws Exception
	 */
	private static PHPDocBlock innerParsePHPDoc(int offset, BufferedReader reader) throws IOException, Exception // $codepro.audit.disable
	{
		StringBuffer moduleData = new StringBuffer();
		try
		{
			char[] buf = new char[1024];
			int numRead = 0;
			while ((numRead = reader.read(buf)) != -1) // $codepro.audit.disable
			{
				String readData = String.valueOf(buf, 0, numRead);
				moduleData.append(readData);
			}
		}
		finally
		{
			if (reader != null)
			{
				try
				{
					reader.close();
				}
				catch (IOException e)
				{
					IdeLog.logWarning(PHPEditorPlugin.getDefault(),
							"Error closing a BufferedReader in the PDTPHPModuleIndexer", e,//$NON-NLS-1$
							PHPEditorPlugin.INDEXER_SCOPE);
				}
			}
		}

		String contents = moduleData.toString();
		int b = -1;
		for (int a = offset; a >= 0; a--)
		{
			char c = contents.charAt(a);
			if (c == '(')
			{
				b = a;
				break;
			}
			if (c == '\r' || c == '\n')
			{
				b = a;
				break;
			}
		}
		if (b != -1)
		{
			String str = contents.substring(b, offset);
			if (str.indexOf(';') == -1)
			{
				offset = b;
			}
			// System.out.println(str);
		}
		// TODO: Shalom - Get the version from the module?
		PHPVersion version = PHPVersionProvider.getDefaultPHPVersion();
		// TODO - Perhaps we'll need to pass a preference value for the 'short-tags' instead of passing 'true' by
		// default.
		ASTParser parser = ASTParser.newParser(new StringReader(contents), version, true); // $codepro.audit.disable
																							// closeWhereCreated
		Program program = parser.createAST(null);

		CommentsVisitor commentsVisitor = new CommentsVisitor();
		program.accept(commentsVisitor);
		List<Comment> _comments = commentsVisitor.getComments();

		return findPHPDocComment(_comments, offset, contents);
	}

	/**
	 * Returns the function documentation from a given {@link IPHPDocBlock}.
	 * 
	 * @param block
	 *            - The block to convert to a {@link FunctionDocumentation}.
	 * @return FunctionDocumentation or null.
	 */
	public static FunctionDocumentation getFunctionDocumentation(IPHPDoc block)
	{
		if (block == null)
		{
			return null;
		}
		FunctionDocumentation result = new FunctionDocumentation();

		result.setDescription(block.getShortDescription());

		IPHPDocTag[] tags = block.getTags();
		if (tags != null)
		{
			for (IPHPDocTag tag : tags)
			{
				switch (tag.getTagKind())
				{
					case PHPDocTag.VAR:
					{
						String value = tag.getValue();
						if (value == null)
						{
							continue;
						}
						TypedDescription typeDescr = new TypedDescription();
						typeDescr.addType(value);
						result.addVar(typeDescr);
						break;
					}
					case PHPDocTag.PARAM:
						String value = tag.getValue();
						if (value == null)
						{
							continue;
						}
						String[] parsedValue = parseParamTagValue(value);
						TypedDescription typeDescr = new TypedDescription();
						typeDescr.setName(parsedValue[0]);
						if (parsedValue[1] != null)
						{
							typeDescr.addType(parsedValue[1]);
						}
						if (parsedValue[2] != null)
						{
							typeDescr.setDescription(parsedValue[2]);
						}
						result.addParam(typeDescr);
						break;
					case PHPDocTag.RETURN:
						String returnTagValue = tag.getValue().trim();
						if (returnTagValue == null)
						{
							continue;
						}
						String[] returnTypes = returnTagValue.split("\\|"); //$NON-NLS-1$
						for (String returnType : returnTypes)
						{
							returnTagValue = clean(returnType.trim());
							returnTagValue = firstWord(returnTagValue);
							result.getReturn().addType(returnTagValue);
						}
						break;
				}
			}
		}

		return result;
	}

	/**
	 * Locates a var comment above the given offset (e.g. <code><b>@var $a Type</b></code>)
	 * 
	 * @param comments
	 * @param offset
	 * @param content
	 * @return An {@link VarComment}
	 */
	public static VarComment findTypedVarComment(List<Comment> comments, int offset, String content)
	{
		Comment c = getCommentByType(comments, offset, content, Comment.TYPE_MULTILINE);
		if (c instanceof VarComment)
		{
			return (VarComment) c;
		}
		return null;
	}

	/**
	 * Finds the PHPDoc comment that appears right above the given offset. In case there is no comment, or there are
	 * non-white characters between the offset and the comment, this method returns null.
	 * 
	 * @param comments
	 *            - The list of comments as parsed with the AST
	 * @param offset
	 *            - offset to start search from.
	 * @param content
	 *            - The file content
	 * @return IPHPDocBlock The PhpDoc, or null.
	 */
	public static PHPDocBlock findPHPDocComment(List<Comment> comments, int offset, String content)
	{
		return (PHPDocBlock) getCommentByType(comments, offset, content, Comment.TYPE_PHPDOC);
	}

	/**
	 * Locate a comment that appears right above the given index (separated only with whitespace chars).
	 * 
	 * @param comments
	 * @param offset
	 * @param content
	 * @param type
	 *            The comment's type (e.g. Comment.TYPE_PHPDOC, Comment.TYPE_PHPDOC | Comment.TYPE_SINGLE_LINE etc.)
	 * @return A comment, or null if none was found.
	 */
	public static Comment getCommentByType(List<Comment> comments, int offset, String content, int type)
	{
		if (comments == null || comments.isEmpty())
		{
			return null;
		}

		Comment nearestComment = null;

		int commentIndex = findComment(comments, offset);
		if (commentIndex < 0)
		{
			// The nearest comment we found should always have a negative value, as it should never overlap with the
			// given offset
			nearestComment = comments.get(-commentIndex - 1);
		}
		if (nearestComment == null)
		{
			return null;
		}

		if ((nearestComment.getCommentType() & type) == 0)
		{
			return null;
		}

		if (content != null)
		{
			// checking if we have anything but whitespace between comment end and
			// offset
			if (offset - 2 < 0 || nearestComment.getEnd() >= content.length() || offset - 2 >= content.length())
			{
				return null;
			}

			// checking if we have anything but white spaces between comment end and offset
			for (int i = nearestComment.getEnd() + 1; i < offset - 1; i++)
			{
				char ch = content.charAt(i);
				if (!Character.isWhitespace(ch))
				{
					return null;
				}
			}
		}
		return nearestComment;
	}

	/**
	 * Perform a binary search for a comment that appears right on top of the given offset
	 */
	private static int findComment(List<Comment> comments, int offset)
	{
		int low = 0;
		int high = comments.size() - 1;

		while (low <= high)
		{
			int mid = (low + high) >>> 1;
			Comment midVal = (Comment) comments.get(mid);
			int cmp = midVal.getStart() - offset;

			if (cmp < 0)
			{
				low = mid + 1;
			}
			else if (cmp > 0)
			{
				high = mid - 1;
			}
			else
			{
				return mid; // key found
			}
		}
		return -low; // key not found.
	}

	/**
	 * Gets the first word of a sentence.
	 * 
	 * @param str
	 *            - string.
	 * @return first word of a sentence.
	 */
	private static String firstWord(String str)
	{
		int firstSpacePos = -1;
		for (int i = 0; i < str.length(); i++)
		{
			char ch = str.charAt(i);
			if (Character.isWhitespace(ch))
			{
				firstSpacePos = i;
				break;
			}
		}

		if (firstSpacePos == -1)
		{
			return str;
		}
		else if (firstSpacePos == 0)
		{
			return EMPTY_STRING;
		}
		else
		{
			return str.substring(0, firstSpacePos);
		}
	}

	/**
	 * Parses parameter tag value.
	 * 
	 * @param toParse
	 * @return array of parse results: first element is parameter name (without the $ symbol), next is parameter type if
	 *         available and the third is parameter description if available.
	 */
	private static String[] parseParamTagValue(String toParse)
	{
		if (toParse == null || toParse.length() == 0)
		{
			return null;
		}

		String[] parts = toParse.split("\\s+"); //$NON-NLS-1$
		if (parts == null || parts.length == 0)
		{
			return null;
		}

		String[] result = new String[3];

		boolean isJSLike = false;
		if (parts[0].contains(OPEN_BRACKET) || parts[0].contains(CLOSE_BRACKET))
		{
			isJSLike = true;
		}
		if (parts[0].contains(DOLLAR))
		{
			isJSLike = true;
		}
		if (parts.length > 1 && (parts[1].contains(DOLLAR)))
		{
			isJSLike = true;
		}

		if (isJSLike)
		{
			if (parts.length == 1)
			{
				result[0] = clean(parts[0]);
			}
			else if (parts.length == 2)
			{
				result[0] = clean(parts[1]);
				result[1] = clean(parts[0]);
			}
			else
			{
				result[0] = clean(parts[1]);
				result[1] = clean(parts[0]);
				StringBuffer buf = new StringBuffer();
				for (int i = 2; i < parts.length; i++)
				{
					buf.append(parts[i]);
					if (i != parts.length)
					{
						buf.append(' ');
					}
				}
				result[2] = buf.toString();
			}
		}
		else
		{
			if (parts.length == 1)
			{
				result[0] = clean(parts[0]);
			}
			else if (parts.length == 2)
			{
				result[0] = clean(parts[0]);
				result[1] = clean(parts[1]);
			}
			else
			{
				result[0] = clean(parts[0]);
				result[1] = clean(parts[1]);
				StringBuffer buf = new StringBuffer();
				for (int i = 2; i < parts.length; i++)
				{
					buf.append(parts[i]);
					if (i != parts.length)
					{
						buf.append(' ');
					}
				}
				result[2] = buf.toString();
			}
		}

		return result;
	}

	/**
	 * Removes curves around the string.
	 * 
	 * @param in
	 *            - input.
	 * @return string with curves removed.
	 */
	private static String removeCurves(String in)
	{
		String result = in;
		if (result.startsWith(OPEN_BRACKET))
		{
			result = result.substring(1);
		}

		if (result.equals(CLOSE_BRACKET))
		{
			return EMPTY_STRING;
		}

		if (result.endsWith(CLOSE_BRACKET))
		{
			result = result.substring(0, result.length() - 1);
		}

		return result;
	}

	/**
	 * Cleans the string from curves and dollar.
	 * 
	 * @param in
	 *            - input.
	 * @return cleansed string
	 */
	private static String clean(String in)
	{
		String res1 = removeCurves(in);
		return removeDollar(res1);
	}

	/**
	 * Removes dollar symbol.
	 * 
	 * @param in
	 *            - input string.
	 * @return cleansed string.
	 */
	private static String removeDollar(String in)
	{
		if (in.startsWith(DOLLAR))
		{
			return in.substring(1);
		}

		return in;
	}

	public static String computeDocumentation(FunctionDocumentation documentation, IDocument document, String name)
	{
		String additionalInfo = Messages.PHPDocUtils_noAvailableDocs;
		StringBuilder bld = new StringBuilder();

		bld.append("<b>" + name + "</b><br>"); //$NON-NLS-1$ //$NON-NLS-2$

		if (documentation != null)
		{

			String longDescription = documentation.getDescription();
			longDescription = longDescription.replaceAll("\r\n", "<br>"); //$NON-NLS-1$ //$NON-NLS-2$
			longDescription = longDescription.replaceAll("\r", "<br>"); //$NON-NLS-1$ //$NON-NLS-2$
			longDescription = longDescription.replaceAll("\n", "<br>"); //$NON-NLS-1$ //$NON-NLS-2$
			if (longDescription.length() > 0)
			{
				bld.append(longDescription);
				bld.append("<br>"); //$NON-NLS-1$
			}
			TypedDescription[] tagsAsArray = documentation.getParams();
			// buf.append("<br>"); //$NON-NLS-1$
			for (int a = 0; a < tagsAsArray.length; a++)
			{

				bld.append("<br>"); //$NON-NLS-1$

				bld.append("@<b>"); //$NON-NLS-1$
				bld.append("param "); //$NON-NLS-1$
				// buf.append();
				bld.append("</b>"); //$NON-NLS-1$
				bld.append(tagsAsArray[a].getName());
				bld.append(' ');
				for (String s : tagsAsArray[a].getTypes())
				{
					bld.append(s);
					bld.append(' ');
				}
				bld.append(' ');
				bld.append(ContentAssistUtils.truncateLineIfNeeded(tagsAsArray[a].getDescription()));
			}

		}
		else
		{
			bld.append(additionalInfo);
		}

		if (documentation != null)
		{
			TypedDescription return1 = documentation.getReturn();
			if (return1 != null)
			{
				String[] types = return1.getTypes();
				if (types.length > 0)
				{
					bld.append("<br>"); //$NON-NLS-1$
					bld.append("@<b>return </b>"); //$NON-NLS-1$
					bld.append(ContentAssistUtils.truncateLineIfNeeded(return1.getDescription()));

					StringBuilder typesBuilder = new StringBuilder();
					for (int a = 0; a < types.length; a++)
					{
						typesBuilder.append(types[a]);
						typesBuilder.append(' ');
					}
					bld.append(ContentAssistUtils.truncateLineIfNeeded(typesBuilder.toString()));
				}
			}

			List<TypedDescription> vars = documentation.getVars();
			if (vars != null)
			{
				for (TypedDescription var : vars)
				{

					if (var != null)
					{
						String[] types = var.getTypes();
						if (types.length > 0)
						{
							bld.append("<br>"); //$NON-NLS-1$
							bld.append("<b>"); //$NON-NLS-1$
							bld.append(Messages.PHPDocUtils_documentedType);
							bld.append("</b>"); //$NON-NLS-1$
							bld.append(var.getDescription());

							for (int a = 0; a < types.length; a++)
							{
								bld.append(types[a]);
								bld.append(' ');
							}
						}
					}
				}
			}
		}
		// Specifically look for HTML 'input' tags and change their open and close chars. The HTML rendering does
		// not
		// remove them when the hover is rendered, introducing form inputs in the hover popup.
		// @See https://aptana.lighthouseapp.com/projects/35272/tickets/1653
		Matcher inputMatcher = INPUT_TAG_PATTERN.matcher(bld.toString());
		int addedOffset = 0;
		while (inputMatcher.find())
		{
			int start = inputMatcher.start();
			int end = inputMatcher.end();
			bld.replace(start + addedOffset, start + addedOffset + 1, "&lt;"); //$NON-NLS-1$
			addedOffset += 2;
			bld.replace(end + addedOffset, end + addedOffset + 1, "&gt;"); //$NON-NLS-1$
			addedOffset += 4;
		}
		additionalInfo = bld.toString();
		return additionalInfo;
	}

	/**
	 * Returns a list of {@link VarComment}s within a specified start and end offsets.
	 * 
	 * @param comments
	 *            A sorted list of comments
	 * @param start
	 * @param end
	 */
	public static List<VarComment> findTypedVarComments(List<Comment> comments, int start, int end)
	{
		List<VarComment> result = new LinkedList<VarComment>();
		// locate the last comment in the given list and create a result list from all the VarComments on top of it,
		// till we hit the start offset.
		// We use a linked list to append comments at the start of the result with better performance.
		int commentIndex = findComment(comments, end);
		if (commentIndex < 0)
		{
			// The nearest comment we found should always have a negative value, as it should never overlap with the
			// given offset
			commentIndex = -commentIndex - 1;
		}
		if (commentIndex >= comments.size())
		{
			// off bounds
			return result;
		}
		for (; commentIndex > -1; commentIndex--)
		{
			Comment comment = comments.get(commentIndex);
			if (comment.getStart() < start)
			{
				break;
			}
			if (comment instanceof VarComment)
			{
				result.add(0, (VarComment) comment);
			}
		}
		return result;
	}
}
