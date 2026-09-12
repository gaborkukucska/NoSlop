import re
import time

def parse_relative_time(text):
    now = int(time.time() * 1000)
    if not text:
        return now
    
    match = re.search(r"(\d+)\s+([a-zA-Z]+)", text)
    if match:
        amount = int(match.group(1))
        unit = match.group(2).lower()
        multiplier = 0
        if unit.startswith("second"): multiplier = 1000
        elif unit.startswith("minute"): multiplier = 60000
        elif unit.startswith("hour"): multiplier = 3600000
        elif unit.startswith("day"): multiplier = 86400000
        elif unit.startswith("week"): multiplier = 604800000
        elif unit.startswith("month"): multiplier = 2592000000
        elif unit.startswith("year"): multiplier = 31536000000
        
        if multiplier > 0:
            return now - (amount * multiplier)
            
    return now

print(parse_relative_time("Streamed 2 days ago"))
print(parse_relative_time("19 minutes ago"))
print(parse_relative_time("about 1 month ago"))
print(int(time.time() * 1000))
