package apperror

import (
	"errors"
	"net/http"
)

var (
	ErrNotFound     = errors.New("not found")
	ErrConflict     = errors.New("already exists")
	ErrInvalidInput = errors.New("invalid input")
	ErrUnauthorized = errors.New("unauthorized")
)

type AppError struct {
	Code    int
	Message string
	Err     error
}

func (e *AppError) Error() string {
	if e.Err != nil {
		return e.Err.Error()
	}
	return e.Message
}

func (e *AppError) Unwrap() error { return e.Err }

func New(code int, message string, err error) *AppError {
	return &AppError{Code: code, Message: message, Err: err}
}

func NotFound(msg string) *AppError {
	return New(http.StatusNotFound, msg, ErrNotFound)
}

func Conflict(msg string) *AppError {
	return New(http.StatusConflict, msg, ErrConflict)
}

func BadRequest(msg string) *AppError {
	return New(http.StatusBadRequest, msg, ErrInvalidInput)
}

func HTTPCode(err error) int {
	var appErr *AppError
	if errors.As(err, &appErr) {
		return appErr.Code
	}
	return http.StatusInternalServerError
}
