import os
import re
from collections import defaultdict

def extract_functions(filepath):
    with open(filepath, 'r') as f:
        content = f.read()

    # match functions
    pattern = r'fun\s+([a-zA-Z0-9_]+)\s*\([^)]*\)\s*(?::\s*[a-zA-Z0-9_<>?]+)?\s*\{'
    matches = re.finditer(pattern, content)
    
    functions = []
    for match in matches:
        func_name = match.group(1)
        start_idx = match.end() - 1
        
        brace_count = 0
        end_idx = start_idx
        for i in range(start_idx, len(content)):
            if content[i] == '{':
                brace_count += 1
            elif content[i] == '}':
                brace_count -= 1
                if brace_count == 0:
                    end_idx = i + 1
                    break
        
        body = content[start_idx:end_idx]
        functions.append((func_name, body.strip(), match.start()))
        
    return functions

func_bodies = defaultdict(list)
empty_functions = []

for root, _, files in os.walk('app/src/main/java'):
    for file in files:
        if file.endswith('.kt'):
            path = os.path.join(root, file)
            funcs = extract_functions(path)
            for name, body, _ in funcs:
                if body == '{}' or body == '{\n}':
                    empty_functions.append((path, name))
                # strip whitespace for comparison
                stripped_body = re.sub(r'\s+', '', body)
                if len(stripped_body) > 10:  # Ignore very small bodies
                    func_bodies[stripped_body].append((path, name))

for body, occurrences in func_bodies.items():
    if len(occurrences) > 1:
        print(f"Duplicate function body found in: {occurrences}")

print("Empty functions:")
for path, name in empty_functions:
    print(f"{path} : {name}")

