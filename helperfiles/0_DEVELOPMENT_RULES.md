<!-- # START OF FILE helperfiles/0_DEVELOPMENT_RULES.md -->
# Development Rules for LLMs and Other AI Systems!
# **IMPORTANT**
**DO NOT** confuse parts of this framework with the parts of your own system!!!

# During development please follow these rules:
*   Unless you've received other specific tasks, follow a phased implementation as outlined in the `helperfiles/3_PROJECT_STATUS.toml` file.
*   Maintain `README.md`, `helperfiles/3_PROJECT_STATUS.toml` (update status) and `SESSION_NUMBER_TITLE.md`, updating them at the end of every development run.
*   Write the location and name of every file in its first line like `<!-- # START OF FILE subfolder/file_name.extension -->`, make sure you also use `//!` or any other methods (depending on the programming language) in front of that statement as needed to properly block out this line.
*   Do NOT remove functional code even if it is yet incomplete, but rather complete what is missing.
*   Measure in predicted "generation token length" instead of any units of "time" when estimating the length of planned work, as that is more representative of how "long" a planned task will take you.
*   Whenever available use the log files to find clues. These files might be very large so first search them for warnings, errors or other specific strings, then use the time stamps to find more detailed debug logs around those times.
*   Maintain code consistency.