from playwright.sync_api import sync_playwright, expect
import time

def run(playwright):
    browser = playwright.chromium.launch(headless=True)
    context = browser.new_context()
    page = context.new_page()

    # Go to the register page
    page.goto("http://localhost:3000/register")

    # Wait for the register form to be visible to ensure the page has loaded
    expect(page.get_by_label("Username")).to_be_visible(timeout=10000)

    # Register
    page.get_by_label("Username").fill("testuser")
    page.get_by_label("Password").fill("password")
    page.get_by_role("button", name="Register").click()

    # Go to the login page
    page.goto("http://localhost:3000/login")

    # Wait for the login form to be visible to ensure the page has loaded
    expect(page.get_by_label("Username")).to_be_visible(timeout=10000)

    # Log in
    page.get_by_label("Username").fill("testuser")
    page.get_by_label("Password").fill("password")
    page.get_by_role("button", name="Login").click()

    # Wait for navigation to the main page and for the header to be visible
    expect(page.get_by_role("heading", name="NoSlop")).to_be_visible(timeout=10000)

    # Take the screenshot
    page.screenshot(path="verification.png")
    browser.close()

with sync_playwright() as playwright:
    run(playwright)
