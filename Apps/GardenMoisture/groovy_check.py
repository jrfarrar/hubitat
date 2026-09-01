"""Structural checks for Hubitat Groovy - the ones a compiler would catch."""
import re, sys

TYPES = r'(?:Integer|String|BigDecimal|Long|Boolean|List|Map|Double|Float|def)'

def strip(src):
    s = re.sub(r'/\*.*?\*/', '', src, flags=re.S)
    s = re.sub(r'"(?:\\.|[^"\\\n])*"', '""', s)
    s = re.sub(r"'(?:\\.|[^'\\\n])*'", "''", s)
    s = re.sub(r'//[^\n]*', '', s)
    return s

def methods(src):
    """Yield (name, startline, body) for each top-level method."""
    lines = src.split("\n")
    out, i = [], 0
    while i < len(lines):
        m = re.match(r'^\s*(?:private|public|def)\s+(?:[\w\.\[\]<>]+\s+)?(\w+)\s*\(', lines[i])
        if m and lines[i].rstrip().endswith("{"):
            depth, body, j = 0, [], i
            while j < len(lines):
                depth += lines[j].count("{") - lines[j].count("}")
                body.append((j + 1, lines[j]))
                j += 1
                if depth <= 0 and len(body) > 1:
                    break
            out.append((m.group(1), i + 1, body))
            i = j
        else:
            i += 1
    return out

def check(path):
    raw = open(path).read()
    src = strip(raw)
    problems = []

    for a, b, name in [('{','}','braces'), ('(',')','parens'), ('[',']','brackets')]:
        if src.count(a) != src.count(b):
            problems.append(f"unbalanced {name}: {src.count(a)}/{src.count(b)}")

    # THE ONE THAT BIT US: same local declared twice in one method scope.
    for mname, mline, body in methods(src):
        seen = {}
        for lineno, line in body:
            for d in re.finditer(rf'^\s*{TYPES}\s+(\w+)\s*=', line):
                v = d.group(1)
                if v in seen:
                    problems.append(
                        f"{mname}(): '{v}' declared twice "
                        f"(lines {seen[v]} and {lineno}) - Groovy will refuse to compile")
                else:
                    seen[v] = lineno

    defs = set(re.findall(rf'^\s*(?:private|public|def)\s+(?:[\w\.\[\]<>]+\s+)?(\w+)\s*\(', raw, re.M))
    for ref in set(re.findall(r'(?:runIn|runEvery\w*|schedule|unschedule|asynchttpGet)\s*\(\s*(?:[^,()]+,\s*)?"(\w+)"', raw)):
        if ref not in defs:
            problems.append(f"scheduled method '{ref}' is not defined")
    for ref in set(re.findall(r'subscribe\([^,]+,\s*"[^"]+",\s*(\w+)\s*\)', raw)):
        if ref not in defs:
            problems.append(f"subscribe handler '{ref}' is not defined")

    v = re.search(r'VERSION = "([^"]+)"', raw)
    print(f"{path}  v{v.group(1) if v else '-'}")
    for p in problems:
        print(f"   *** {p}")
    if not problems:
        print("   clean")
    return len(problems)

sys.exit(sum(check(p) for p in sys.argv[1:]))
