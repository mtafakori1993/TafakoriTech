package apperror

import (
	"errors"
	"net/http"

	"github.com/gin-gonic/gin"
)

// Sentinel errors for domain-level error classification.
var (
	ErrNotFound     = errors.New("not found")
	ErrConflict     = errors.New("conflict")
	ErrInvalidInput = errors.New("invalid input")
	ErrForbidden    = errors.New("forbidden")
)

type errorResponse struct {
	Error string `json:"error"`
}

// Respond maps a domain error to the appropriate HTTP response.
// Unknown errors map to 500 Internal Server Error.
func Respond(c *gin.Context, err error) {
	switch {
	case errors.Is(err, ErrNotFound):
		c.JSON(http.StatusNotFound, errorResponse{Error: err.Error()})
	case errors.Is(err, ErrConflict):
		c.JSON(http.StatusConflict, errorResponse{Error: err.Error()})
	case errors.Is(err, ErrInvalidInput):
		c.JSON(http.StatusBadRequest, errorResponse{Error: err.Error()})
	case errors.Is(err, ErrForbidden):
		c.JSON(http.StatusForbidden, errorResponse{Error: err.Error()})
	default:
		c.JSON(http.StatusInternalServerError, errorResponse{Error: "internal server error"})
	}
}
