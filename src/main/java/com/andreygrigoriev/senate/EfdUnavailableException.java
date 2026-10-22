package com.andreygrigoriev.senate;

/** eFD refused the request (403) or sent us back to the agreement page. */
public class EfdUnavailableException extends RuntimeException {

    public EfdUnavailableException(String message) {
        super(message);
    }
}
