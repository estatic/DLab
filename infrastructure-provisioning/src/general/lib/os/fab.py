# *****************************************************************************
#
# Copyright (c) 2016, EPAM SYSTEMS INC
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# ******************************************************************************

from fabric.api import *
from fabric.contrib.files import exists
import logging
import os
import random
import sys
import string
import json, uuid, time, datetime, csv
from dlab.meta_lib import *
from dlab.actions_lib import *
import dlab.actions_lib
import re


def ensure_pip(requisites):
    try:
        if not exists('/home/{}/.ensure_dir/pip_path_added'.format(os.environ['conf_os_user'])):
            sudo('echo PATH=$PATH:/usr/local/bin/:/opt/spark/bin/ >> /etc/profile')
            sudo('echo export PATH >> /etc/profile')
            sudo('pip install -U pip --no-cache-dir')
            sudo('pip install -U ' + requisites + ' --no-cache-dir')
            sudo('touch /home/{}/.ensure_dir/pip_path_added'.format(os.environ['conf_os_user']))
        return True
    except:
        return False


def dataengine_dir_prepare(cluster_dir):
    local('mkdir -p ' + cluster_dir)


def install_pip_pkg(requisites, pip_version, lib_group):
    status = list()
    error_parser = "Could not|No matching|ImportError:|failed|EnvironmentError:"
    try:
        if pip_version == 'pip3':
            if not exists('/bin/pip3'):
                sudo('ln -s /bin/pip3.5 /bin/pip3')
        sudo('{} install -U pip setuptools'.format(pip_version))
        sudo('{} install -U pip --no-cache-dir'.format(pip_version))
        sudo('{} install --upgrade pip'.format(pip_version))
        for pip_pkg in requisites:
            sudo('{0} install {1} --no-cache-dir 2>&1 | if ! grep -w -E  "({2})" >  /tmp/{0}install_{1}.log; then  echo "" > /tmp/{0}install_{1}.log;fi'.format(pip_version, pip_pkg, error_parser))
            err = sudo('cat /tmp/{0}install_{1}.log'.format(pip_version, pip_pkg)).replace('"', "'")
            replaced_pip_pkg = pip_pkg.replace("_", "-")
            sudo('{0} freeze | if ! grep -w {1} > /tmp/{0}install_{1}.list; then  echo "" > /tmp/{0}install_{1}.list;fi'.format(pip_version, replaced_pip_pkg))
            res = sudo('cat /tmp/{0}install_{1}.list'.format(pip_version, replaced_pip_pkg))
            if res:
                ansi_escape = re.compile(r'\x1b[^m]*m')
                ver = ansi_escape.sub('', res).split("\r\n")
                version = [i for i in ver if replaced_pip_pkg in i][0].split('==')[1]
                status.append({"group": "{}".format(lib_group), "name": pip_pkg, "version": version, "status": "installed"})
            else:
                status.append({"group": "{}".format(lib_group), "name": pip_pkg, "status": "failed", "error_message": err})
        return status
    except:
        return "Failed to install {} packages".format(pip_version)


def id_generator(size=10, chars=string.digits + string.ascii_letters):
    return ''.join(random.choice(chars) for _ in range(size))


def prepare_disk(os_user):
    if not exists('/home/' + os_user + '/.ensure_dir/disk_ensured'):
        try:
            sudo('sed -i "/azure_resource-part1/ s|/mnt|/media|g" /etc/fstab')
            sudo('grep "azure_resource-part1" /etc/fstab > /dev/null &&  umount -f /mnt/ || true')
            sudo('mount -a')
            disk_name = sudo("lsblk | grep disk | awk '{print $1}' | sort | tail -n 1")
            sudo('''bash -c 'echo -e "o\nn\np\n1\n\n\nw" | fdisk /dev/{}' '''.format(disk_name))
            sudo('mkfs.ext4 -F /dev/{}1'.format(disk_name))
            sudo('mount /dev/{}1 /opt/'.format(disk_name))
            sudo(''' bash -c "echo '/dev/{}1 /opt/ ext4 errors=remount-ro 0 1' >> /etc/fstab" '''.format(disk_name))
            sudo('touch /home/' + os_user + '/.ensure_dir/disk_ensured')
        except:
            sys.exit(1)


def ensure_local_spark(os_user, spark_link, spark_version, hadoop_version, local_spark_path):
    if not exists('/home/' + os_user + '/.ensure_dir/local_spark_ensured'):
        try:
            sudo('wget ' + spark_link + ' -O /tmp/spark-' + spark_version + '-bin-hadoop' + hadoop_version + '.tgz')
            sudo('tar -zxvf /tmp/spark-' + spark_version + '-bin-hadoop' + hadoop_version + '.tgz -C /opt/')
            sudo('mv /opt/spark-' + spark_version + '-bin-hadoop' + hadoop_version + ' ' + local_spark_path)
            sudo('chown -R ' + os_user + ':' + os_user + ' ' + local_spark_path)
            sudo('touch /home/' + os_user + '/.ensure_dir/local_spark_ensured')
        except:
            sys.exit(1)


def install_dataengine_spark(spark_link, spark_version, hadoop_version, spark_dir, os_user):
    local('wget ' + spark_link + ' -O /tmp/spark-' + spark_version + '-bin-hadoop' + hadoop_version + '.tgz')
    local('tar -zxvf /tmp/spark-' + spark_version + '-bin-hadoop' + hadoop_version + '.tgz -C /opt/')
    local('mv /opt/spark-' + spark_version + '-bin-hadoop' + hadoop_version + ' ' + spark_dir)
    local('chown -R ' + os_user + ':' + os_user + ' ' + spark_dir)


def configure_dataengine_spark(jars_dir, spark_dir, local_spark_dir):
    local("jar_list=`find {} -name '*.jar' | tr '\\n' ','` ; echo \"spark.jars   $jar_list\" >> \
          /tmp/notebook_spark-defaults_local.conf".format(jars_dir))
    local('mv /tmp/notebook_spark-defaults_local.conf  {}conf/spark-defaults.conf'.format(spark_dir))
    local('cp {0}conf/core-site.xml {1}conf/'.format(local_spark_dir, spark_dir))


def prepare(dataengine_service_dir, yarn_dir):
    local('mkdir -p ' + dataengine_service_dir)
    local('mkdir -p ' + yarn_dir)
    local('sudo mkdir -p /opt/python/')
    result = os.path.exists(dataengine_service_dir + 'usr/')
    return result


def configuring_notebook(dataengine_service_version):
    jars_path = '/opt/' + dataengine_service_version + '/jars/'
    local("""sudo bash -c "find """ + jars_path + """ -name '*netty*' | xargs rm -f" """)


def append_result(error, exception=''):
    ts = time.time()
    st = datetime.datetime.fromtimestamp(ts).strftime('%Y-%m-%d %H:%M:%S')
    with open('/root/result.json', 'a+') as f:
        text = f.read()
    if len(text) == 0:
        res = '{"error": ""}'
        with open('/root/result.json', 'w') as f:
            f.write(res)
    with open("/root/result.json") as f:
        data = json.load(f)
    if exception:
        data['error'] = data['error'] + " [Error-" + st + "]:" + error + " Exception: " + str(exception)
    else:
        data['error'] = data['error'] + " [Error-" + st + "]:" + error
    with open("/root/result.json", 'w') as f:
        json.dump(data, f)
    print data


def put_resource_status(resource, status, dlab_path, os_user, hostname):
    env['connection_attempts'] = 100
    keyfile = os.environ['conf_key_dir'] + os.environ['conf_key_name'] + ".pem"
    env.key_filename = [keyfile]
    env.host_string = os_user + '@' + hostname
    sudo('python ' + dlab_path + 'tmp/resource_status.py --resource {} --status {}'.format(resource, status))


def configure_jupyter(os_user, jupyter_conf_file, templates_dir, jupyter_version):
    if not exists('/home/' + os_user + '/.ensure_dir/jupyter_ensured'):
        try:
            sudo('pip2 install notebook=={} --no-cache-dir'.format(jupyter_version))
            sudo('pip2 install jupyter --no-cache-dir')
            sudo('pip3.5 install notebook=={} --no-cache-dir'.format(jupyter_version))
            sudo('pip3.5 install jupyter --no-cache-dir')
            sudo('rm -rf ' + jupyter_conf_file)
            run('jupyter notebook --generate-config --config ' + jupyter_conf_file)
            with cd('/home/{}'.format(os_user)):
                run('mkdir -p ~/.jupyter/custom/')
                run('echo "#notebook-container { width: auto; }" > ~/.jupyter/custom/custom.css')
            sudo('echo "c.NotebookApp.ip = \'*\'" >> ' + jupyter_conf_file)
            sudo('echo c.NotebookApp.open_browser = False >> ' + jupyter_conf_file)
            sudo('echo \'c.NotebookApp.cookie_secret = b"' + id_generator() + '"\' >> ' + jupyter_conf_file)
            sudo('''echo "c.NotebookApp.token = u''" >> ''' + jupyter_conf_file)
            sudo('echo \'c.KernelSpecManager.ensure_native_kernel = False\' >> ' + jupyter_conf_file)
            put(templates_dir + 'jupyter-notebook.service', '/tmp/jupyter-notebook.service')
            sudo("chmod 644 /tmp/jupyter-notebook.service")
            if os.environ['application'] == 'tensor':
                sudo("sed -i '/ExecStart/s|-c \"|-c \"export LD_LIBRARY_PATH=$LD_LIBRARY_PATH:/opt/cudnn/lib64:/usr/local/cuda/lib64; |g' /tmp/jupyter-notebook.service")
            elif os.environ['application'] == 'deeplearning':
                sudo("sed -i '/ExecStart/s|-c \"|-c \"export LD_LIBRARY_PATH=$LD_LIBRARY_PATH:/opt/cudnn/lib64:"
                     "/usr/local/cuda/lib64 ; export PYTHONPATH=/home/" + os_user + "/caffe/python:/home/" + os_user +
                     "/caffe2/build:$PYTHONPATH ; |g' /tmp/jupyter-notebook.service")
            sudo("sed -i 's|CONF_PATH|{}|' /tmp/jupyter-notebook.service".format(jupyter_conf_file))
            sudo("sed -i 's|OS_USR|{}|' /tmp/jupyter-notebook.service".format(os_user))
            sudo('\cp /tmp/jupyter-notebook.service /etc/systemd/system/jupyter-notebook.service')
            sudo('chown -R {0}:{0} /home/{0}/.local'.format(os_user))
            sudo('mkdir -p /mnt/var')
            sudo('chown {0}:{0} /mnt/var'.format(os_user))
            if os.environ['application'] == 'jupyter':
                sudo('jupyter-kernelspec remove -f python2')
                sudo('jupyter-kernelspec remove -f python3')
            sudo("systemctl daemon-reload")
            sudo("systemctl enable jupyter-notebook")
            sudo("systemctl start jupyter-notebook")
            #run('mkdir -p ~/.git')
            #put('/root/scripts/ipynb_output_filter.py', '~/.git/ipynb_output_filter.py', mode=0755)
            #run('echo "*.ipynb    filter=clear_output_ipynb" > ~/.gitattributes')
            #run('git config --global core.attributesfile ~/.gitattributes')
            #run('git config --global filter.clear_output_ipynb.clean ~/.git/ipynb_output_filter.py')
            sudo('touch /home/{}/.ensure_dir/jupyter_ensured'.format(os_user))
        except:
            sys.exit(1)


def ensure_pyspark_local_kernel(os_user, pyspark_local_path_dir, templates_dir, spark_version):
    if not exists('/home/' + os_user + '/.ensure_dir/pyspark_local_kernel_ensured'):
        try:
            sudo('mkdir -p ' + pyspark_local_path_dir)
            sudo('touch ' + pyspark_local_path_dir + 'kernel.json')
            put(templates_dir + 'pyspark_local_template.json', '/tmp/pyspark_local_template.json')
            sudo(
                "PYJ=`find /opt/spark/ -name '*py4j*.zip' | tr '\\n' ':' | sed 's|:$||g'`; sed -i 's|PY4J|'$PYJ'|g' /tmp/pyspark_local_template.json")
            sudo('sed -i "s|SP_VER|' + spark_version + '|g" /tmp/pyspark_local_template.json')
            sudo('sed -i \'/PYTHONPATH\"\:/s|\(.*\)"|\\1/home/{0}/caffe/python:/home/{0}/caffe2/build:"|\' /tmp/pyspark_local_template.json'.format(os_user))
            sudo('\cp /tmp/pyspark_local_template.json ' + pyspark_local_path_dir + 'kernel.json')
            sudo('touch /home/' + os_user + '/.ensure_dir/pyspark_local_kernel_ensured')
        except:
            sys.exit(1)


def ensure_py3spark_local_kernel(os_user, py3spark_local_path_dir, templates_dir, spark_version):
    if not exists('/home/' + os_user + '/.ensure_dir/py3spark_local_kernel_ensured'):
        try:
            sudo('mkdir -p ' + py3spark_local_path_dir)
            sudo('touch ' + py3spark_local_path_dir + 'kernel.json')
            put(templates_dir + 'py3spark_local_template.json', '/tmp/py3spark_local_template.json')
            sudo(
                "PYJ=`find /opt/spark/ -name '*py4j*.zip' | tr '\\n' ':' | sed 's|:$||g'`; sed -i 's|PY4J|'$PYJ'|g' /tmp/py3spark_local_template.json")
            sudo('sed -i "s|SP_VER|' + spark_version + '|g" /tmp/py3spark_local_template.json')
            sudo('sed -i \'/PYTHONPATH\"\:/s|\(.*\)"|\\1/home/{0}/caffe/python:/home/{0}/caffe2/build:"|\' /tmp/py3spark_local_template.json'.format(os_user))
            sudo('\cp /tmp/py3spark_local_template.json ' + py3spark_local_path_dir + 'kernel.json')
            sudo('touch /home/' + os_user + '/.ensure_dir/py3spark_local_kernel_ensured')
        except:
            sys.exit(1)


def pyspark_kernel(kernels_dir, dataengine_service_version, cluster_name, spark_version, bucket, user_name, region, os_user='',
                   application='', pip_mirror=''):
    spark_path = '/opt/{0}/{1}/spark/'.format(dataengine_service_version, cluster_name)
    local('mkdir -p {0}pyspark_{1}/'.format(kernels_dir, cluster_name))
    kernel_path = '{0}pyspark_{1}/kernel.json'.format(kernels_dir, cluster_name)
    template_file = "/tmp/pyspark_dataengine-service_template.json"
    with open(template_file, 'r') as f:
        text = f.read()
    text = text.replace('CLUSTER_NAME', cluster_name)
    text = text.replace('SPARK_VERSION', 'Spark-' + spark_version)
    text = text.replace('SPARK_PATH', spark_path)
    text = text.replace('PYTHON_SHORT_VERSION', '2.7')
    text = text.replace('PYTHON_FULL_VERSION', '2.7')
    text = text.replace('PYTHON_PATH', '/usr/bin/python2.7')
    text = text.replace('DATAENGINE-SERVICE_VERSION', dataengine_service_version)
    with open(kernel_path, 'w') as f:
        f.write(text)
    local('touch /tmp/kernel_var.json')
    local("PYJ=`find /opt/{0}/{1}/spark/ -name '*py4j*.zip' | tr '\\n' ':' | sed 's|:$||g'`; cat {2} | sed 's|PY4J|'$PYJ'|g' | sed \'/PYTHONPATH\"\:/s|\(.*\)\"|\\1/home/{3}/caffe/python:/home/{3}/caffe2/build:\"|\' > /tmp/kernel_var.json".
          format(dataengine_service_version, cluster_name, kernel_path, os_user))
    local('sudo mv /tmp/kernel_var.json ' + kernel_path)
    get_cluster_python_version(region, bucket, user_name, cluster_name)
    with file('/tmp/python_version') as f:
        python_version = f.read()
    # python_version = python_version[0:3]
    if python_version != '\n':
        installing_python(region, bucket, user_name, cluster_name, application, pip_mirror)
        local('mkdir -p {0}py3spark_{1}/'.format(kernels_dir, cluster_name))
        kernel_path = '{0}py3spark_{1}/kernel.json'.format(kernels_dir, cluster_name)
        template_file = "/tmp/pyspark_dataengine-service_template.json"
        with open(template_file, 'r') as f:
            text = f.read()
        text = text.replace('CLUSTER_NAME', cluster_name)
        text = text.replace('SPARK_VERSION', 'Spark-' + spark_version)
        text = text.replace('SPARK_PATH', spark_path)
        text = text.replace('PYTHON_SHORT_VERSION', python_version[0:3])
        text = text.replace('PYTHON_FULL_VERSION', python_version[0:3])
        text = text.replace('PYTHON_PATH', '/opt/python/python' + python_version[:5] + '/bin/python' +
                            python_version[:3])
        text = text.replace('DATAENGINE-SERVICE_VERSION', dataengine_service_version)
        with open(kernel_path, 'w') as f:
            f.write(text)
        local('touch /tmp/kernel_var.json')
        local("PYJ=`find /opt/{0}/{1}/spark/ -name '*py4j*.zip' | tr '\\n' ':' | sed 's|:$||g'`; cat {2} | sed 's|PY4J|'$PYJ'|g' | sed \'/PYTHONPATH\"\:/s|\(.*\)\"|\\1/home/{3}/caffe/python:/home/{3}/caffe2/build:\"|\' > /tmp/kernel_var.json"
              .format(dataengine_service_version, cluster_name, kernel_path, os_user))
        local('sudo mv /tmp/kernel_var.json {}'.format(kernel_path))


def ensure_ciphers():
    sudo('echo -e "\nKexAlgorithms curve25519-sha256@libssh.org,diffie-hellman-group-exchange-sha256" >> /etc/ssh/sshd_config')
    sudo('echo -e "Ciphers aes256-gcm@openssh.com,aes128-gcm@openssh.com,chacha20-poly1305@openssh.com,aes256-ctr,aes192-ctr,aes128-ctr" >> /etc/ssh/sshd_config')
    sudo('echo -e "\tKexAlgorithms curve25519-sha256@libssh.org,diffie-hellman-group-exchange-sha256" >> /etc/ssh/ssh_config')
    sudo('echo -e "\tCiphers aes256-gcm@openssh.com,aes128-gcm@openssh.com,chacha20-poly1305@openssh.com,aes256-ctr,aes192-ctr,aes128-ctr" >> /etc/ssh/ssh_config')
    try:
        sudo('service ssh restart')
    except:
        sudo('service sshd restart')


def install_r_pkg(requisites):
    status = list()
    error_parser = "ERROR:|error:|Cannot|failed|Please run|requires"
    try:
        for r_pkg in requisites:
            sudo('R -e \'install.packages("{0}", repos="http://cran.us.r-project.org", dep=TRUE)\'  2>&1 | if ! grep -w -E  "({1})" >  /tmp/install_{0}.log; then  echo "" > /tmp/install_{0}.log;fi'.format(r_pkg, error_parser))
            err = sudo('cat /tmp/install_{0}.log'.format(r_pkg)).replace('"', "'")
            sudo('R -e \'installed.packages()[,c(3:4)]\' | if ! grep -w {0} > /tmp/install_{0}.list; then  echo "" > /tmp/install_{0}.list;fi'.format(r_pkg))
            res = sudo('cat /tmp/install_{0}.list'.format(r_pkg))
            if res:
                ansi_escape = re.compile(r'\x1b[^m]*m')
                version = ansi_escape.sub('', res).split("\r\n")[0].split('"')[1]
                status.append({"group": "r_pkg", "name": r_pkg, "version": version, "status": "installed"})
            else:
                status.append({"group": "r_pkg", "name": r_pkg, "status": "failed", "error_message": err})
        return status
    except:
        return "Fail to install R packages"


def get_available_r_pkgs():
    try:
        r_pkgs = dict()
        sudo('R -e \'write.table(available.packages(contriburl="http://cran.us.r-project.org/src/contrib"), file="/tmp/r.csv", row.names=F, col.names=F, sep=",")\'')
        get("/tmp/r.csv", "r.csv")
        with open('r.csv', 'rb') as csvfile:
            reader = csv.reader(csvfile, delimiter=',')
            for row in reader:
                r_pkgs[row[0]] = row[1]
        return r_pkgs
    except:
        sys.exit(1)


def ensure_toree_local_kernel(os_user, toree_link, scala_kernel_path, files_dir, scala_version, spark_version):
    if not exists('/home/' + os_user + '/.ensure_dir/toree_local_kernel_ensured'):
        try:
            sudo('pip install ' + toree_link + ' --no-cache-dir')
            sudo('ln -s /opt/spark/ /usr/local/spark')
            sudo('jupyter toree install')
            sudo('mv ' + scala_kernel_path + 'lib/* /tmp/')
            put(files_dir + 'toree-assembly-0.2.0.jar', '/tmp/toree-assembly-0.2.0.jar')
            sudo('mv /tmp/toree-assembly-0.2.0.jar ' + scala_kernel_path + 'lib/')
            sudo(
                'sed -i "s|Apache Toree - Scala|Local Apache Toree - Scala (Scala-' + scala_version +
                ', Spark-' + spark_version + ')|g" ' + scala_kernel_path + 'kernel.json')
            sudo('touch /home/' + os_user + '/.ensure_dir/toree_local_kernel_ensured')
        except:
            sys.exit(1)


def install_ungit(os_user):
    if not exists('/home/{}/.ensure_dir/ungit_ensured'.format(os_user)):
        try:
            sudo('npm -g install ungit')
            put('/root/templates/ungit.service', '/tmp/ungit.service')
            sudo("sed -i 's|OS_USR|{}|' /tmp/ungit.service".format(os_user))
            sudo('mv -f /tmp/ungit.service /etc/systemd/system/ungit.service')
            run('git config --global http.proxy $http_proxy')
            run('git config --global https.proxy $https_proxy')
            run('git config --global user.name "Example User"')
            run('git config --global user.email "example@example.com"')
            run('mkdir -p ~/.git/templates/hooks')
            put('/root/scripts/git_pre_commit.py', '~/.git/templates/hooks/pre-commit', mode=0755)
            run('git config --global init.templatedir ~/.git/templates')
            run('touch ~/.gitignore')
            run('git config --global core.excludesfile ~/.gitignore')
            run('echo ".ipynb_checkpoints/" >> ~/.gitignore')
            run('echo "spark-warehouse/" >> ~/.gitignore')
            run('echo "metastore_db/" >> ~/.gitignore')
            run('echo "derby.log" >> ~/.gitignore')
            sudo('systemctl daemon-reload')
            sudo('systemctl enable ungit.service')
            sudo('systemctl start ungit.service')
            sudo('touch /home/{}/.ensure_dir/ungit_ensured'.format(os_user))
        except:
            sys.exit(1)


def set_mongo_parameters(client, mongo_parameters):
    for i in mongo_parameters:
        client.dlabdb.settings.insert_one({"_id": i, "value": mongo_parameters[i]})
