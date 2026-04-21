// package ru.configplatform.configserver.config;

// @ControllerAdvice
// public class GlobalExceptionHandler {
//     private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

//     @ExceptionHandler(Exception.class)
//     public ResponseEntity<String> handleException(Exception ex) {
//         logger.error("Exception caught: {}", ex.getMessage(), ex);
//         return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("An error occurred");
//     }
// }
