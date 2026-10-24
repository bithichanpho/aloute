INSERT INTO public.roles (name)
SELECT 'ROLE_HOST'
WHERE NOT EXISTS (SELECT 1 FROM public.roles WHERE name = 'ROLE_HOST');

CREATE INDEX IF NOT EXISTS idx_users_created_at ON public.users (created_at);
CREATE INDEX IF NOT EXISTS idx_users_active ON public.users (is_active);
CREATE INDEX IF NOT EXISTS idx_communities_created_at ON public.communities (created_at);
