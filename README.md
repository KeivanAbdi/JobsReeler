# JobsReeler

This is a sample project that demonstrates the capabilities of the [DataReeler](https://github.com/KeivanAbdi/DataReeler) library. It showcases how to build an interactive, real-time data processing stream for filtering and exploring job postings from LinkedIn.

## What it Does

This project provides a concrete example of a `DataReeler` application. It is configured to:

1.  Scrape Scala job postings in Germany from LinkedIn.
2.  Enrich and filter the raw job data through a multi-stage pipeline. This includes:
    *   Using an AI model via Ollama to determine the language of the job posting (e.g., German or English).
    *   Using an AI model via Gemini to analyze the job description and determine the required German language proficiency level.
    *   Cross-referencing the hiring company with a list of companies that are known to provide visa sponsorship.
    *   Filtering jobs based on keywords in the title and description.
    *   Filtering by job type, such as Hybrid or On-site.
3.  Display the filtered job postings in an interactive web interface, where the user can control the flow of data.

The purpose of this example is to demonstrate how to build a "human-in-the-loop" application, where a user can interactively explore and refine a large stream of data.

## How to Run

This project is built with sbt. To run the application, you will need to have sbt installed.

1.  **Configure Cookies**: You will need to provide your LinkedIn cookies in a JSON file. The file should contain a JSON array where each object has `name`, `value`, and `domain` keys. You can use a browser extension like [Get cookies.txt LOCALLY](https://chromewebstore.google.com/detail/get-cookiestxt-locally/cclelndahbckbenkjhflpdbgdldlbecc) for Chrome to export your cookies from your LinkedIn account.
2.  **Configure Secrets**: Create a configuration file named `secret.conf` in the `src/main/resources` directory. You can use [`secret.template.conf`](src/main/resources/secret.template.conf) as a template to see the required structure and fill in your details. 
3.  **Run the Application**:
    ```bash
    sbt run
    ```
3.  **View the UI**: Open your web browser and navigate to `http://localhost:8080`.

## Disclaimer

This project is for educational purposes only. Please be aware that scraping LinkedIn is a violation of their [User Agreement](https://www.linkedin.com/legal/user-agreement) (see section 8.2). By using this software, you acknowledge that you are doing so at your own risk, and the author of this project is not responsible for any consequences that may arise from your actions, including but not limited to the suspension or termination of your LinkedIn account.

It is strongly recommended that you use a separate, secondary LinkedIn account for any testing or use of this project to minimize the risk to your primary account. Your use of this software constitutes your agreement to these terms.

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
