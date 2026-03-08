/*******************************************************************************
 * Copyright (c) 2026 Christoph Läubrich and others.
 * This program and the accompanying materials are made available under the terms
 * of the Eclipse Public License 2.0 which accompanies this distribution, and is
 * available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 * Christoph Läubrich - initial API and implementation
 *******************************************************************************/
package io.github.laeubi.copilot.cli.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON serialization/deserialization for the MCP protocol. Avoids
 * external dependencies by implementing a simple recursive-descent parser and a
 * serializer that handles Map, List, String, Number, Boolean, and null.
 */
public final class Json {

	private Json() {
	}

	// --- Serialization ---

	public static String serialize(Object value) {
		StringBuilder sb = new StringBuilder();
		writeValue(sb, value);
		return sb.toString();
	}

	@SuppressWarnings("unchecked")
	private static void writeValue(StringBuilder sb, Object value) {
		if (value == null) {
			sb.append("null");
		} else if (value instanceof String s) {
			writeString(sb, s);
		} else if (value instanceof Number n) {
			if (n instanceof Double d && d == Math.floor(d) && !Double.isInfinite(d)) {
				sb.append((long) d.doubleValue());
			} else if (n instanceof Float f && f == Math.floor(f) && !Float.isInfinite(f)) {
				sb.append((int) f.floatValue());
			} else {
				sb.append(n);
			}
		} else if (value instanceof Boolean b) {
			sb.append(b);
		} else if (value instanceof Map<?, ?> map) {
			writeObject(sb, (Map<String, Object>) map);
		} else if (value instanceof List<?> list) {
			writeArray(sb, list);
		} else {
			writeString(sb, value.toString());
		}
	}

	private static void writeString(StringBuilder sb, String s) {
		sb.append('"');
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
			case '"' -> sb.append("\\\"");
			case '\\' -> sb.append("\\\\");
			case '\n' -> sb.append("\\n");
			case '\r' -> sb.append("\\r");
			case '\t' -> sb.append("\\t");
			case '\b' -> sb.append("\\b");
			case '\f' -> sb.append("\\f");
			default -> {
				if (c < 0x20) {
					sb.append(String.format("\\u%04x", (int) c));
				} else {
					sb.append(c);
				}
			}
			}
		}
		sb.append('"');
	}

	private static void writeObject(StringBuilder sb, Map<String, Object> map) {
		sb.append('{');
		boolean first = true;
		for (var entry : map.entrySet()) {
			if (!first)
				sb.append(',');
			first = false;
			writeString(sb, entry.getKey());
			sb.append(':');
			writeValue(sb, entry.getValue());
		}
		sb.append('}');
	}

	private static void writeArray(StringBuilder sb, List<?> list) {
		sb.append('[');
		for (int i = 0; i < list.size(); i++) {
			if (i > 0)
				sb.append(',');
			writeValue(sb, list.get(i));
		}
		sb.append(']');
	}

	// --- Deserialization ---

	public static Object parse(String json) {
		if (json == null || json.isBlank()) {
			return null;
		}
		Parser p = new Parser(json.trim());
		Object result = p.parseValue();
		return result;
	}

	@SuppressWarnings("unchecked")
	public static Map<String, Object> parseObject(String json) {
		Object result = parse(json);
		if (result instanceof Map<?, ?> map) {
			return (Map<String, Object>) map;
		}
		return null;
	}

	private static class Parser {
		private final String input;
		private int pos;

		Parser(String input) {
			this.input = input;
			this.pos = 0;
		}

		Object parseValue() {
			skipWhitespace();
			if (pos >= input.length())
				return null;
			char c = input.charAt(pos);
			return switch (c) {
			case '"' -> parseString();
			case '{' -> parseObjectValue();
			case '[' -> parseArrayValue();
			case 't', 'f' -> parseBoolean();
			case 'n' -> parseNull();
			default -> parseNumber();
			};
		}

		private String parseString() {
			expect('"');
			StringBuilder sb = new StringBuilder();
			while (pos < input.length()) {
				char c = input.charAt(pos++);
				if (c == '"')
					return sb.toString();
				if (c == '\\') {
					if (pos >= input.length())
						break;
					char esc = input.charAt(pos++);
					switch (esc) {
					case '"' -> sb.append('"');
					case '\\' -> sb.append('\\');
					case '/' -> sb.append('/');
					case 'n' -> sb.append('\n');
					case 'r' -> sb.append('\r');
					case 't' -> sb.append('\t');
					case 'b' -> sb.append('\b');
					case 'f' -> sb.append('\f');
					case 'u' -> {
						String hex = input.substring(pos, pos + 4);
						sb.append((char) Integer.parseInt(hex, 16));
						pos += 4;
					}
					default -> sb.append(esc);
					}
				} else {
					sb.append(c);
				}
			}
			return sb.toString();
		}

		private Map<String, Object> parseObjectValue() {
			expect('{');
			Map<String, Object> map = new LinkedHashMap<>();
			skipWhitespace();
			if (pos < input.length() && input.charAt(pos) == '}') {
				pos++;
				return map;
			}
			while (pos < input.length()) {
				skipWhitespace();
				String key = parseString();
				skipWhitespace();
				expect(':');
				Object value = parseValue();
				map.put(key, value);
				skipWhitespace();
				if (pos < input.length() && input.charAt(pos) == ',') {
					pos++;
				} else {
					break;
				}
			}
			skipWhitespace();
			if (pos < input.length() && input.charAt(pos) == '}')
				pos++;
			return map;
		}

		private List<Object> parseArrayValue() {
			expect('[');
			java.util.ArrayList<Object> list = new java.util.ArrayList<>();
			skipWhitespace();
			if (pos < input.length() && input.charAt(pos) == ']') {
				pos++;
				return list;
			}
			while (pos < input.length()) {
				list.add(parseValue());
				skipWhitespace();
				if (pos < input.length() && input.charAt(pos) == ',') {
					pos++;
				} else {
					break;
				}
			}
			skipWhitespace();
			if (pos < input.length() && input.charAt(pos) == ']')
				pos++;
			return list;
		}

		private Boolean parseBoolean() {
			if (input.startsWith("true", pos)) {
				pos += 4;
				return Boolean.TRUE;
			}
			if (input.startsWith("false", pos)) {
				pos += 5;
				return Boolean.FALSE;
			}
			throw new IllegalStateException("Expected boolean at " + pos);
		}

		private Object parseNull() {
			if (input.startsWith("null", pos)) {
				pos += 4;
				return null;
			}
			throw new IllegalStateException("Expected null at " + pos);
		}

		private Number parseNumber() {
			int start = pos;
			if (pos < input.length() && input.charAt(pos) == '-')
				pos++;
			while (pos < input.length() && Character.isDigit(input.charAt(pos)))
				pos++;
			boolean isDouble = false;
			if (pos < input.length() && input.charAt(pos) == '.') {
				isDouble = true;
				pos++;
				while (pos < input.length() && Character.isDigit(input.charAt(pos)))
					pos++;
			}
			if (pos < input.length() && (input.charAt(pos) == 'e' || input.charAt(pos) == 'E')) {
				isDouble = true;
				pos++;
				if (pos < input.length() && (input.charAt(pos) == '+' || input.charAt(pos) == '-'))
					pos++;
				while (pos < input.length() && Character.isDigit(input.charAt(pos)))
					pos++;
			}
			String num = input.substring(start, pos);
			if (isDouble) {
				return Double.parseDouble(num);
			}
			long l = Long.parseLong(num);
			if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) {
				return (int) l;
			}
			return l;
		}

		private void expect(char expected) {
			skipWhitespace();
			if (pos < input.length() && input.charAt(pos) == expected) {
				pos++;
			}
		}

		private void skipWhitespace() {
			while (pos < input.length() && Character.isWhitespace(input.charAt(pos))) {
				pos++;
			}
		}
	}
}
