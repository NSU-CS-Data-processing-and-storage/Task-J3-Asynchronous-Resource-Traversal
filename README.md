# Task-J3-Asynchronous-Resource-Traversal

### Overview

Implement an asynchronous web spider that traverses an HTTP server starting from `/`, follows all `successors`, collects every `message`, and outputs the messages sorted lexicographically. 

### Server

* Start: `java -jar Task_J3-server.jar <student_id> [port]` (default port `8080`).
* `student_id` determines a unique dataset per student. 
* Response (HTTP 200, `application/json`):

  ```json
  { "message": "some text", "successors": ["path_1", "path_2", ...] }
  ```

  Each `path_i` is a valid next URL path on the same server. 

### Constraints

* Each request may take **0–12 seconds** to complete.
* The full dataset is guaranteed retrievable within **≈120 seconds**. 

### Requirements

* Use **Java 21 virtual threads** to achieve high concurrency. 

### Output

* Print the collected `message` values in **lexicographic order**, one per line. 
