package io.pockethive.dbquery;

import java.util.ArrayList;
import java.util.List;

class NamedSqlParser {

  NamedSql parse(String sqlTemplate) {
    String sql = DbQueryWorkerConfig.normalise(sqlTemplate);
    if (sql == null) {
      throw new IllegalArgumentException("sqlTemplate is required");
    }

    StringBuilder jdbc = new StringBuilder(sql.length());
    List<String> names = new ArrayList<>();
    boolean singleQuoted = false;
    boolean doubleQuoted = false;
    boolean lineComment = false;
    boolean blockComment = false;

    for (int i = 0; i < sql.length(); i++) {
      char c = sql.charAt(i);
      char next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';

      if (lineComment) {
        jdbc.append(c);
        if (c == '\n') {
          lineComment = false;
        }
        continue;
      }
      if (blockComment) {
        jdbc.append(c);
        if (c == '*' && next == '/') {
          jdbc.append(next);
          i++;
          blockComment = false;
        }
        continue;
      }
      if (singleQuoted) {
        jdbc.append(c);
        if (c == '\'' && next == '\'') {
          jdbc.append(next);
          i++;
        } else if (c == '\'') {
          singleQuoted = false;
        }
        continue;
      }
      if (doubleQuoted) {
        jdbc.append(c);
        if (c == '"') {
          doubleQuoted = false;
        }
        continue;
      }

      if (c == '-' && next == '-') {
        jdbc.append(c).append(next);
        i++;
        lineComment = true;
        continue;
      }
      if (c == '/' && next == '*') {
        jdbc.append(c).append(next);
        i++;
        blockComment = true;
        continue;
      }
      if (c == '\'') {
        jdbc.append(c);
        singleQuoted = true;
        continue;
      }
      if (c == '"') {
        jdbc.append(c);
        doubleQuoted = true;
        continue;
      }
      if (c == ':' && next != ':' && isIdentifierStart(next) && previousIsNotColon(sql, i)) {
        int start = i + 1;
        int end = start + 1;
        while (end < sql.length() && isIdentifierPart(sql.charAt(end))) {
          end++;
        }
        names.add(sql.substring(start, end));
        jdbc.append('?');
        i = end - 1;
        continue;
      }
      jdbc.append(c);
    }

    return new NamedSql(jdbc.toString(), names);
  }

  private static boolean previousIsNotColon(String sql, int index) {
    return index == 0 || sql.charAt(index - 1) != ':';
  }

  private static boolean isIdentifierStart(char c) {
    return Character.isLetter(c) || c == '_';
  }

  private static boolean isIdentifierPart(char c) {
    return Character.isLetterOrDigit(c) || c == '_';
  }
}
