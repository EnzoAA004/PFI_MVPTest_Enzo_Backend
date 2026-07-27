package ar.edu.uade.pfi.backend.config;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

class CorsResponseFilterTest {
    @Test
    void originsWithSurroundingSpacesAreNormalizedBeforeMatching() throws Exception {
        CorsResponseFilter filter = new CorsResponseFilter("  https://spaced.example.com  , http://localhost:5173 ", "");
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(request.getMethod()).thenReturn("GET");
        when(request.getHeader("Origin")).thenReturn("https://spaced.example.com");

        filter.doFilter(request, response, chain);

        verify(response).setHeader("Access-Control-Allow-Origin", "https://spaced.example.com");
        verify(chain).doFilter(request, response);
    }

    @Test
    void emptyAllowedOriginsRejectsEveryOriginSafely() throws Exception {
        CorsResponseFilter filter = new CorsResponseFilter("", "");
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(request.getMethod()).thenReturn("GET");
        when(request.getHeader("Origin")).thenReturn("https://anything.example.com");

        filter.doFilter(request, response, chain);

        verify(response, org.mockito.Mockito.never()).setHeader(org.mockito.ArgumentMatchers.eq("Access-Control-Allow-Origin"), org.mockito.ArgumentMatchers.anyString());
        verify(chain).doFilter(request, response);
    }

    @Test
    void wildcardOriginIsNeverHonoredEvenIfConfigured() throws Exception {
        CorsResponseFilter filter = new CorsResponseFilter("*", "*");
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(request.getMethod()).thenReturn("GET");
        when(request.getHeader("Origin")).thenReturn("https://random.example.com");

        filter.doFilter(request, response, chain);

        verify(response, org.mockito.Mockito.never()).setHeader(org.mockito.ArgumentMatchers.eq("Access-Control-Allow-Origin"), org.mockito.ArgumentMatchers.anyString());
    }
}
