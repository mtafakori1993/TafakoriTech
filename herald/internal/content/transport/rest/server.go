package rest

import (
	"net/http"

	"github.com/gin-gonic/gin"
	"go.uber.org/zap"

	"github.com/mtafakori/herald/internal/content/service"
	"github.com/mtafakori/herald/internal/content/transport/rest/handler"
	"github.com/mtafakori/herald/internal/content/transport/rest/middleware"
)

// NewRouter builds and returns the Gin engine with all routes registered.
func NewRouter(
	apiKey, jwtSecret string,
	log *zap.Logger,
	pageSvc service.PageService,
	postSvc service.PostService,
	articleSvc service.ArticleService,
	publishSvc service.PublishService,
	searchSvc service.SearchService,
) *gin.Engine {
	r := gin.New()
	r.Use(gin.Recovery())
	r.Use(middleware.RequestLogger(log))
	r.Use(corsMiddleware())

	r.GET("/health", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{"status": "ok", "service": "herald"})
	})

	pageH := handler.NewPageHandler(pageSvc, log)
	postH := handler.NewPostHandler(postSvc, log)
	articleH := handler.NewArticleHandler(articleSvc, log)
	publishH := handler.NewPublishHandler(publishSvc, log)
	searchH := handler.NewSearchHandler(searchSvc, log)

	v1 := r.Group("/api/v1")

	// Public read endpoints
	v1.GET("/pages/:slug", pageH.GetBySlug)
	v1.GET("/posts", postH.List)
	v1.GET("/posts/:slug", postH.GetBySlug)
	v1.GET("/articles", articleH.List)
	v1.GET("/articles/:slug", articleH.GetBySlug)
	v1.GET("/search", searchH.Search)

	// Authenticated write endpoints
	auth := v1.Group("", middleware.JWTAuth(jwtSecret))
	auth.GET("/pages", pageH.List)
	auth.POST("/pages", pageH.Create)
	auth.PUT("/pages/:id", pageH.Update)
	auth.DELETE("/pages/:id", pageH.Delete)
	auth.GET("/admin/posts", postH.AdminList)
	auth.POST("/posts", postH.Create)
	auth.PUT("/posts/:id", postH.Update)
	auth.DELETE("/posts/:id", postH.Delete)
	auth.GET("/admin/articles", articleH.AdminList)
	auth.POST("/articles", articleH.Create)
	auth.PUT("/articles/:id", articleH.Update)
	auth.DELETE("/articles/:id", articleH.Delete)
	auth.POST("/publish/:id", publishH.Publish)
	auth.POST("/unpublish/:id", publishH.Unpublish)

	return r
}

func corsMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		c.Header("Access-Control-Allow-Origin", "*")
		c.Header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
		c.Header("Access-Control-Allow-Headers", "Content-Type, X-API-Key")
		if c.Request.Method == http.MethodOptions {
			c.AbortWithStatus(http.StatusNoContent)
			return
		}
		c.Next()
	}
}
