package ar.edu.uade.pfi.backend.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Metadata del documento OpenAPI: lo que springdoc no puede deducir leyendo los controllers.
 *
 * <p>El esquema de seguridad se declara una sola vez y se aplica a toda la API, porque {@code
 * AuthFilter} es fail-closed: salvo la lista corta de rutas publicas, todo pide un Bearer JWT.
 * Declararlo por operacion seria repetir en 60 lugares una regla que vive en un filtro.
 */
@Configuration
public class OpenApiConfig {

  private static final String BEARER_SCHEME = "bearerAuth";

  @Bean
  public OpenAPI pfiOpenApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("PFI Backend - RM Lumbar")
                .version("0.1.0")
                .description(
                    """
                    API del backend del PFI de analisis asistido de RM lumbar.

                    El backend no ejecuta modelos, no emite diagnostico clinico y no convierte la \
                    salida del modulo de IA en una decision medica. Toda respuesta de pipeline o \
                    reporte preserva `humanReviewRequired=true` y `notClinicalDiagnosis=true`: el \
                    profesional revisa, acepta, observa o descarta.

                    ## Autenticacion

                    Salvo `POST /api/auth/**` de alta y login y `GET /api/system/health`, toda \
                    operacion exige `Authorization: Bearer <accessToken>`. El token sale de \
                    `POST /api/auth/verify-login` (o de `POST /api/auth/demo-doctor` en local, si \
                    esta habilitado).

                    Una cuenta sin aprobar (`PENDING_APPROVAL`) obtiene token pero solo puede \
                    llamar a `/api/auth/me` y `/api/auth/settings`; el resto responde 403.

                    ## Errores

                    Todos los errores comparten un unico cuerpo, con `code` de un catalogo cerrado \
                    (`ApiErrorCode`), `traceId` correlacionable con los logs y `retryable`. Un 5xx \
                    nunca devuelve el mensaje de la excepcion, ni SQL, ni rutas internas.

                    ## Assets

                    Las imagenes de una corrida se piden siempre al backend \
                    (`/api/ai/assets/...`), nunca al modulo de IA: las rutas internas de ese \
                    servicio no se publican.
                    """)
                .license(new License().name("Uso academico - PFI UADE")))
        .components(
            new Components()
                .addSecuritySchemes(
                    BEARER_SCHEME,
                    new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description(
                            "Access token devuelto por /api/auth/verify-login o"
                                + " /api/auth/demo-doctor.")))
        .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
  }
}
