/*******************************************************************************
 * Copyright 2014 uniVocity Software Pty Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package com.univocity.parsers.fixed;

import com.univocity.parsers.common.*;
import com.univocity.parsers.common.input.*;

/**
 * A fast and flexible fixed-with parser implementation.
 *
 * @see FixedWidthFormat
 * @see FixedWidthFieldLengths
 * @see FixedWidthParserSettings
 * @see FixedWidthWriter
 * @see AbstractParser
 *
 * @author uniVocity Software Pty Ltd - <a href="mailto:parsers@univocity.com">parsers@univocity.com</a>
 *
 */
public class FixedWidthParser extends AbstractParser<FixedWidthParserSettings> {

	private int[] lengths;
	private int[] rootLengths;

	private final Lookup[] lookaheadFormats;
	private final Lookup[] lookbehindFormats;
	private Lookup lookupFormat;
	private Lookup lookbehindFormat;
	private int maxLookupLength;

	private final boolean ignoreLeadingWhitespace;
	private final boolean ignoreTrailingWhitespace;
	private final boolean skipToNewLine;
	private final boolean recordEndsOnNewLine;
	private final boolean skipEmptyLines;

	private final char padding;
	private final char newLine;

	private int length;
	private boolean initializeLookaheadInput = false;
	private LookaheadCharInputReader lookaheadInput;

	/**
	 * The FixedWidthParser supports all settings provided by {@link FixedWidthParserSettings}, and requires this configuration to be properly initialized.
	 * @param settings the parser configuration
	 */
	public FixedWidthParser(FixedWidthParserSettings settings) {
		super(settings);
		ignoreLeadingWhitespace = settings.getIgnoreLeadingWhitespaces();
		ignoreTrailingWhitespace = settings.getIgnoreTrailingWhitespaces();
		skipToNewLine = settings.getSkipTrailingCharsUntilNewline();
		recordEndsOnNewLine = settings.getRecordEndsOnNewline();
		skipEmptyLines = settings.getSkipEmptyLines();
		lengths = settings.getFieldLengths();
		lookaheadFormats = settings.getLookaheadFormats();
		lookbehindFormats = settings.getLookbehindFormats();

		if (lookaheadFormats != null || lookbehindFormats != null) {
			initializeLookaheadInput = true;
			rootLengths = lengths;
			maxLookupLength = Lookup.calculateMaxLookupLength(lookaheadFormats, lookbehindFormats);

			this.context = new ParsingContextWrapper(context) {
				@Override
				public String[] headers() {
					return lookupFormat != null ? lookupFormat.fieldNames : super.headers();
				}
			};
		}

		FixedWidthFormat format = settings.getFormat();
		padding = format.getPadding();
		newLine = format.getNormalizedNewline();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	protected void parseRecord() {
		if (ch == newLine && skipEmptyLines) {
			return;
		}

		boolean matched = false;
		if (lookaheadFormats != null || lookbehindFormats != null) {
			if (initializeLookaheadInput) {
				initializeLookaheadInput = false;
				this.lookaheadInput = new LookaheadCharInputReader(input);
				this.input = lookaheadInput;
			}

			lookaheadInput.lookahead(maxLookupLength);

			if (lookaheadFormats != null) {
				for (int i = 0; i < lookaheadFormats.length; i++) {
					if (lookaheadInput.matches(ch, lookaheadFormats[i].value)) {
						lengths = lookaheadFormats[i].lengths;
						lookupFormat = lookaheadFormats[i];
						matched = true;
						break;
					}
				}
				if (lookbehindFormats != null && matched) {
					lookbehindFormat = null;
					for (int i = 0; i < lookbehindFormats.length; i++) {
						if (lookaheadInput.matches(ch, lookbehindFormats[i].value)) {
							lookbehindFormat = lookbehindFormats[i];
							break;
						}
					}
				}
			} else {
				for (int i = 0; i < lookbehindFormats.length; i++) {
					if (lookaheadInput.matches(ch, lookbehindFormats[i].value)) {
						lookbehindFormat = lookbehindFormats[i];
						matched = true;
						lengths = rootLengths;
						break;
					}
				}
			}

			if (!matched) {
				if (lookbehindFormat == null) {
					if (rootLengths == null) {
						throw new TextParsingException(context, "Cannot process input with the given configuration. No default field lengths defined and no lookahead/lookbehind value match '" + lookaheadInput.getLookahead(ch) + "'");
					}
					lengths = rootLengths;
					lookupFormat = null;
				} else {
					lengths = lookbehindFormat.lengths;
					lookupFormat = lookbehindFormat;
				}
			}
		}

		int i;
		for (i = 0; i < lengths.length; i++) {
			length = lengths[i];

			skipPadding();

			if (ignoreLeadingWhitespace) {
				skipWhitespace();
			}

			if (recordEndsOnNewLine) {
				readValueUntilNewLine();
				if (ch == newLine) {
					output.valueParsed();
					return;
				}
			} else {
				readValue();
			}
			output.valueParsed();
		}

		if (skipToNewLine) {
			skipToNewLine();
		}

	}

	private void skipToNewLine() {
		while (ch != newLine) {
			ch = input.nextChar();
		}
	}

	private void skipPadding() {
		while (ch == padding && length-- > 0) {
			ch = input.nextChar();
		}
	}

	private void skipWhitespace() {
		while (ch <= ' ' && length-- > 0) {
			ch = input.nextChar();
		}
	}

	private void readValueUntilNewLine() {
		if (ignoreTrailingWhitespace) {
			while (length-- > 0 && ch != newLine) {
				output.appender.appendIgnoringWhitespaceAndPadding(ch);
				ch = input.nextChar();
			}
		} else {
			while (length-- > 0 && ch != newLine) {
				output.appender.appendIgnoringPadding(ch);
				ch = input.nextChar();
			}
		}
	}

	private void readValue() {
		if (ignoreTrailingWhitespace) {
			while (length-- > 0) {
				output.appender.appendIgnoringWhitespaceAndPadding(ch);
				ch = input.nextChar();
			}
		} else {
			while (length-- > 0) {
				output.appender.appendIgnoringPadding(ch);
				ch = input.nextChar();
			}
		}
	}
}
