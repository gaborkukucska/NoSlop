import os
import re

for root, _, files in os.walk('app/src/main/java'):
    for file in files:
        if file.endswith('.kt'):
            path = os.path.join(root, file)
            with open(path, 'r') as f:
                content = f.read()

            pattern = r'fun\s+([a-zA-Z0-9_]+)\s*\([^)]*\)\s*(?::\s*[a-zA-Z0-9_<>?]+)?\s*\{'
            matches = re.finditer(pattern, content)
            
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
                if len(body.strip()) < 10:
                    print(f"Short func in {path}: {func_name} -> {repr(body.strip())}")

