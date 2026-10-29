-- Seed roles
INSERT INTO roles (name, slug, description) VALUES
    ('Super Admin',  'super-admin', 'Full access to all resources'),
    ('Admin',        'admin',       'Administrative access'),
    ('Manager',      'manager',     'Managerial access'),
    ('Operator',     'operator',    'Operational access'),
    ('Viewer',       'viewer',      'Read-only access')
ON CONFLICT (slug) DO NOTHING;

-- Seed permissions
INSERT INTO permissions (name, slug, resource, action, description) VALUES
    -- Users
    ('Create User',         'users.create',         'users',       'create',  'Create new users'),
    ('Read User',           'users.read',           'users',       'read',    'View user details'),
    ('Update User',         'users.update',         'users',       'update',  'Update user information'),
    ('Delete User',         'users.delete',         'users',       'delete',  'Delete users'),
    -- Roles
    ('Create Role',         'roles.create',         'roles',       'create',  'Create new roles'),
    ('Read Role',           'roles.read',           'roles',       'read',    'View role details'),
    ('Update Role',         'roles.update',         'roles',       'update',  'Update role information'),
    ('Delete Role',         'roles.delete',         'roles',       'delete',  'Delete roles'),
    ('Manage Role',         'roles.manage',         'roles',       'manage',  'Manage role assignments'),
    -- Permissions
    ('Create Permission',   'permissions.create',   'permissions', 'create',  'Create new permissions'),
    ('Read Permission',     'permissions.read',     'permissions', 'read',    'View permission details'),
    ('Update Permission',   'permissions.update',   'permissions', 'update',  'Update permission information'),
    ('Delete Permission',   'permissions.delete',   'permissions', 'delete',  'Delete permissions'),
    -- Teams
    ('Create Team',         'teams.create',         'teams',       'create',  'Create new teams'),
    ('Read Team',           'teams.read',           'teams',       'read',    'View team details'),
    ('Update Team',         'teams.update',         'teams',       'update',  'Update team information'),
    ('Delete Team',         'teams.delete',         'teams',       'delete',  'Delete teams'),
    -- Leads
    ('Create Lead',         'leads.create',         'leads',       'create',  'Create new leads'),
    ('Read Lead',           'leads.read',           'leads',       'read',    'View lead details'),
    ('Update Lead',         'leads.update',         'leads',       'update',  'Update lead information'),
    ('Delete Lead',         'leads.delete',         'leads',       'delete',  'Delete leads'),
    ('Manage Lead',         'leads.manage',         'leads',       'manage',  'Manage lead assignments'),
    -- Content
    ('Create Content',      'content.create',       'content',     'create',  'Create new content'),
    ('Read Content',        'content.read',         'content',     'read',    'View content'),
    ('Update Content',      'content.update',       'content',     'update',  'Update content'),
    ('Delete Content',      'content.delete',       'content',     'delete',  'Delete content'),
    ('Publish Content',     'content.publish',      'content',     'publish', 'Publish content')
ON CONFLICT (slug) DO NOTHING;

-- Assign all permissions to super-admin role
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.slug = 'super-admin'
  AND r.deleted_at IS NULL
  AND p.deleted_at IS NULL
ON CONFLICT DO NOTHING;

-- Seed super-admin user (email set via :admin_email psql variable)
INSERT INTO users (first_name, last_name, email, status)
VALUES ('Super', 'Admin', :'admin_email', 'active')
ON CONFLICT (email) DO NOTHING;

INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id
FROM users u
CROSS JOIN roles r
WHERE u.email = :'admin_email'
  AND r.slug = 'super-admin'
  AND u.deleted_at IS NULL
  AND r.deleted_at IS NULL
ON CONFLICT DO NOTHING;
