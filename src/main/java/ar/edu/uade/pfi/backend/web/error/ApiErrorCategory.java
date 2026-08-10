package ar.edu.uade.pfi.backend.web.error;

/** Stable, low-cardinality classification for every error the API can return. */
public enum ApiErrorCategory {
  AUTHENTICATION,
  AUTHORIZATION,
  VALIDATION,
  RESOURCE,
  DATABASE,
  AI_UPSTREAM,
  AI_CONTRACT,
  SECURITY,
  INTERNAL
}
