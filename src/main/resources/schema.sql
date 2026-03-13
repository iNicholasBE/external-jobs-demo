CREATE TABLE IF NOT EXISTS approval_request (
    id BIGSERIAL PRIMARY KEY,
    job_key VARCHAR(100) NOT NULL,
    product_name VARCHAR(200) NOT NULL,
    content TEXT,
    ai_recommendation VARCHAR(50),
    ai_confidence DOUBLE PRECISION DEFAULT 0,
    status VARCHAR(50) NOT NULL DEFAULT 'ANALYZING',
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);
